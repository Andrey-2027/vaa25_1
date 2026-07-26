package org.ip.service;

import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.MetadataResolver;
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

    public ServiceLocator(ApplicationContext applicationContext, MetadataResolver metadataResolver) {
        this.applicationContext = applicationContext;
        this.metadataResolver = metadataResolver;
    }

    @SuppressWarnings("unchecked")
    public <T extends IdentifiableEntity, ID> BaseService<T, ID> findService(Class<T> entityClass) {
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
        } catch (Exception e) {
            throw new IllegalStateException(
                "No service found for " + entityClass.getSimpleName() + ". " +
                "Expected bean name: '" + serviceName + "'. " +
                "Solutions:\n" +
                "  1. Add serviceClass to @EntityMetadata: serviceClass = YourService.class\n" +
                "  2. Create a @Service class named " + capitalize(serviceName) + "\n" +
                "  3. Rename your service bean to '" + serviceName + "'", e);
        }
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
