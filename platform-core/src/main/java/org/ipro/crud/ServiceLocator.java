package org.ipro.crud;

import org.ipro.identity.IdentifiableEntity;

import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Type-directed поиск data handle для сущности (C4, ADR-0007 §1).
 *
 * <p>Порядок решения ровно один и не зависит от порядка регистрации bean'ов:</p>
 * <ol>
 * <li>типизированный application service, зарегистрированный под своим entity type
 * (typed use case с предметными операциями);</li>
 * <li>иначе canonical generic handle, если descriptor типа его допускает
 * ({@code STANDARD_ROOT} либо {@code INTERNAL_STORE} с явно выданным чтением);</li>
 * <li>иначе отказ с реальной причиной — на вызове, до SQL.</li>
 * </ol>
 *
 * <p>C4.7: резолв по имени бина ({@code <entity>Service}) удалён — это была строка,
 * выводимая из имени класса, а не контракт. Регистрация теперь типизирована: сервис
 * объявляет свой entity type первым аргументом {@link BaseService}, а два сервиса на один
 * тип — ошибка конфигурации на старте, а не «кто позже зарегистрировался».</p>
 *
 * <p>Вынесено в отдельный класс, потому что раньше эта логика была продублирована один в один
 * в {@code FormResolver} и {@code FormCoordinator}.</p>
 */
@Component
public class ServiceLocator implements SmartInitializingSingleton, EntityServiceResolver {

    private final ApplicationContext applicationContext;
    private final MetadataResolver metadataResolver;
    private final SectionMetadataRegistry sectionRegistry;

    /**
     * Canonical data path (C4, ADR-0007 §1). Optional: metadata-only срезы и hand-built
     * тесты сохраняют прежнее поведение, а полный контекст получает default handle для
     * {@code STANDARD_ROOT}, у которого нет application service.
     */
    private org.ipro.data.EntityDataAccessResolver dataAccessResolver;

    /** Типизированные сервисы по entity type; строится после создания синглтонов. */
    private Map<Class<?>, BaseService<?, ?>> typedServices = Map.of();

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

    /**
     * Проиндексировать типизированные сервисы.
     *
     * <p>Хук {@link SmartInitializingSingleton} выбран намеренно: он выполняется после того,
     * как все синглтоны созданы, поэтому индексирование не влияет на граф зависимостей и не
     * может породить цикл (реестр читается только при последующих вызовах), а обнаруженный
     * конфликт останавливает контекст — то есть виден на старте, а не при открытии формы.</p>
     */
    @Override
    public void afterSingletonsInstantiated() {
        Map<Class<?>, BaseService<?, ?>> index = new LinkedHashMap<>();
        Map<Class<?>, String> beanNames = new LinkedHashMap<>();
        Map<String, BaseService> beans = applicationContext.getBeansOfType(BaseService.class);
        for (Map.Entry<String, BaseService> entry : beans.entrySet()) {
            BaseService<?, ?> service = Objects.requireNonNull(entry.getValue(),
                "BaseService bean must not be null");
            Class<?> entityType = entityTypeOf(service.getClass());
            if (entityType == null) {
                throw new IllegalStateException("BaseService bean '" + entry.getKey() + "' ("
                    + service.getClass().getName() + ") does not declare its entity type as the"
                    + " first type argument of BaseService, so it cannot be resolved by type."
                    + " Generic-only registrations are not supported: resolution is type-directed.");
            }
            BaseService<?, ?> previous = index.putIfAbsent(entityType, service);
            if (previous != null) {
                throw new IllegalStateException("More than one BaseService handles "
                    + entityType.getName() + ": " + previous.getClass().getName() + " (bean '"
                    + beanNames.get(entityType) + "') and " + service.getClass().getName()
                    + " (bean '" + entry.getKey() + "'). Resolution is type-directed, so the"
                    + " choice must be unique — remove one registration or replace both with a"
                    + " single use case.");
            }
            beanNames.put(entityType, entry.getKey());
        }
        this.typedServices = Map.copyOf(index);
    }

    @SuppressWarnings("unchecked")
    @Override
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
        // Тип вне metadata-driven golden path (report/settings/telemetry storage) сюда не
        // попадает: он обслуживается своим владельцем и data handle не получает.
        metadataResolver.resolve(entityClass);

        BaseService<?, ?> typed = typedServices.get(entityClass);
        if (typed != null) {
            return (BaseService<T, ID>) typed;
        }

        BaseService<T, ID> canonical = canonicalService(entityClass);
        if (canonical != null) {
            return canonical;
        }
        throw new IllegalStateException(
            "No BaseService found for " + entityClass.getSimpleName() + ". " +
            "Resolution is type-directed, so the options are:\n" +
            "  1. Register a typed service bean implementing BaseService<"
                + entityClass.getSimpleName() + ", ID> — it is discovered by entity type\n" +
            "  2. Leave the type as a STANDARD_ROOT so the canonical generic handle applies\n" +
            "  3. Give the owner an explicit EntityCapabilityOverride granting the needed"
                + " canonical scenarios/intents");
    }

    /**
     * Default data handle canonical path: возвращается только для типа, которому
     * descriptor разрешил canonical доступ. Для остальных — {@code null} (диагностика выше),
     * а не скрытый generic-путь.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> BaseService<T, ID> canonicalService(Class<T> entityClass) {
        if (dataAccessResolver == null) {
            return null;
        }
        return dataAccessResolver.<T, ID>findService(entityClass).orElse(null);
    }

    /** Entity type сервиса — первый аргумент {@link BaseService} в его иерархии. */
    private static Class<?> entityTypeOf(Class<?> serviceClass) {
        Class<?> resolved = ResolvableType.forClass(serviceClass).as(BaseService.class)
            .resolveGeneric(0);
        if (resolved == null || !IdentifiableEntity.class.isAssignableFrom(resolved)) {
            return null;
        }
        return resolved;
    }

}
