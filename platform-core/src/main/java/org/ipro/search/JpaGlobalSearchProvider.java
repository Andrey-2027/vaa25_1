package org.ipro.search;

import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.HasDisplayName;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Default result mapper for a globally searchable JPA entity. */
public final class JpaGlobalSearchProvider<T> implements GlobalSearchProvider<T> {

    private final Class<T> entityClass;
    private final InstanceNameResolver instanceNameResolver;

    public JpaGlobalSearchProvider(Class<T> entityClass) {
        this(entityClass, null);
    }

    public JpaGlobalSearchProvider(Class<T> entityClass,
                                   InstanceNameResolver instanceNameResolver) {
        this.entityClass = entityClass;
        this.instanceNameResolver = instanceNameResolver;
    }

    @Override
    public Class<T> entityClass() {
        return entityClass;
    }

    @Override
    public Object idOf(T entity) {
        Field idField = findIdField(entityClass);
        if (idField != null) {
            try {
                idField.setAccessible(true);
                return idField.get(entity);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Не удалось прочитать идентификатор "
                    + entityClass.getName(), e);
            }
        }
        try {
            Method getId = entityClass.getMethod("getId");
            return getId.invoke(entity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("У сущности нет доступного @Id/getId: "
                + entityClass.getName(), e);
        }
    }

    @Override
    public String displayValue(T entity, GlobalSearchSource source) {
        if (instanceNameResolver != null) {
            String declared = instanceNameResolver.declaredName(entity);
            if (declared != null) {
                return safeText(declared);
            }
        }
        if (entity instanceof HasDisplayName displayName) {
            return safeText(displayName.getDisplayName());
        }
        return entityClass.getSimpleName() + "#" + idOf(entity);
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static Field findIdField(Class<?> entityClass) {
        for (Class<?> current = entityClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    return field;
                }
            }
        }
        return null;
    }
}
