package org.ipro.crud;

import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.crud.IdentifiableEntity;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Динамический поиск Spring-бина {@link BaseService} для сущности — по
 * {@code @EntityMetadata.serviceClass()}, если указан, иначе по конвенции имени бина
 * ({@code <entity>Service}).
 *
 * Вынесено в отдельный класс, потому что раньше эта логика была продублирована один в один
 * в {@code FormResolver} и {@code FormCoordinator}.
 */
@Component
public class ServiceLocator {

    private final ApplicationContext applicationContext;
    private final MetadataResolver metadataResolver;
    private final SectionMetadataRegistry sectionRegistry;

    /**
     * Canonical data path (C4, ADR-0007 §1). Optional: metadata-only срезы и hand-built
     * тесты сохраняют прежнее поведение, а полный контекст получает default handle для
     * {@code STANDARD_ROOT}, у которого нет application service.
     */
    private org.ipro.data.EntityDataAccessResolver dataAccessResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDataAccessResolver(org.ipro.data.EntityDataAccessResolver dataAccessResolver) {
        this.dataAccessResolver = dataAccessResolver;
    }

    public ServiceLocator(ApplicationContext applicationContext, MetadataResolver metadataResolver,
                          SectionMetadataRegistry sectionRegistry) {
        this.applicationContext = applicationContext;
        this.metadataResolver = metadataResolver;
        this.sectionRegistry = sectionRegistry;
    }

    @SuppressWarnings("unchecked")
    public <T extends IdentifiableEntity, ID> BaseService<T, ID> findService(Class<T> entityClass) {
        // Owned-строка секции не бывает самостоятельной сущностью: её чтение не может
        // выразить обязательный предикат владельца (доступ наследуется от агрегата),
        // поэтому у неё нет автономного сервиса/списка. Сообщаем реальную причину
        // вместо совета «создайте service» из ошибки ниже.
        TableSectionMetadataInfo section = sectionRegistry.findByRow(entityClass).orElse(null);
        if (section != null) {
            throw new IllegalStateException(
                entityClass.getSimpleName() + " — строка owned-секции " + section.getKey()
                    + " и не имеет автономного сервиса/списка. Секция редактируется в карточке "
                    + section.getOwnerClass().getSimpleName()
                    + " и сохраняется aggregate boundary (GenericOwnedSectionService).");
        }
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        Class<?> serviceClass = meta.getAnnotation().serviceClass();

        if (serviceClass != null && serviceClass != void.class) {
            try {
                return (BaseService<T, ID>) applicationContext.getBean(serviceClass);
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Service class specified in @EntityMetadata not found: " + serviceClass.getName() + ". " +
                    "Make sure " + serviceClass.getSimpleName() + " is a Spring @Service bean.", e);
            }
        }

        String serviceName = uncapitalize(entityClass.getSimpleName()) + "Service";
        try {
            return (BaseService<T, ID>) applicationContext.getBean(serviceName);
        } catch (Exception missingTypedService) {
            // C4.3: STANDARD_ROOT без application service получает canonical generic handle
            // вместо отказа. Fallback срабатывает только на реально отсутствующий бин: если
            // бин есть, но упал при создании, подменять его generic-путём нельзя — иначе
            // сломанный typed service маскировался бы рабочим default'ом.
            if (missingTypedService
                    instanceof org.springframework.beans.factory.NoSuchBeanDefinitionException) {
                BaseService<T, ID> canonical = canonicalService(entityClass);
                if (canonical != null) {
                    return canonical;
                }
            }
            throw new IllegalStateException(
                "No service found for " + entityClass.getSimpleName() + ". " +
                "Expected bean name: '" + serviceName + "'. " +
                "Solutions:\n" +
                "  1. Add serviceClass to @EntityMetadata: serviceClass = YourService.class\n" +
                "  2. Create a @Service class named " + capitalize(serviceName) + "\n" +
                "  3. Rename your service bean to '" + serviceName + "'", missingTypedService);
        }
    }

    /**
     * Default data handle canonical path: возвращается только для типа, которому
     * descriptor разрешил canonical доступ. Для остальных — {@code null} (прежняя
     * диагностика «создайте service»), а не скрытый generic-путь.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> BaseService<T, ID> canonicalService(Class<T> entityClass) {
        if (dataAccessResolver == null) {
            return null;
        }
        return dataAccessResolver.<T, ID>findService(entityClass).orElse(null);
    }

    private String uncapitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}
