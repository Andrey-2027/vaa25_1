package org.ipro.search;

import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.HasDisplayName;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    @Override
    public GlobalSearchMatch classify(T entity, GlobalSearchSource source, String term) {
        String normalizedTerm = normalize(term);
        String firstSubstringField = null;
        String firstPrefixField = null;
        for (String fieldName : source.searchFields()) {
            Object value = readPath(entity, fieldName);
            if (value == null) {
                continue;
            }
            String normalizedValue = normalize(String.valueOf(value));
            if (normalizedValue.equals(normalizedTerm)) {
                return new GlobalSearchMatch(GlobalSearchMatchKind.EXACT, fieldName);
            }
            if (firstPrefixField == null && normalizedValue.startsWith(normalizedTerm)) {
                firstPrefixField = fieldName;
            }
            if (firstSubstringField == null && normalizedValue.contains(normalizedTerm)) {
                firstSubstringField = fieldName;
            }
        }
        if (firstPrefixField != null) {
            return new GlobalSearchMatch(GlobalSearchMatchKind.PREFIX, firstPrefixField);
        }
        if (firstSubstringField != null) {
            return new GlobalSearchMatch(GlobalSearchMatchKind.SUBSTRING, firstSubstringField);
        }
        // The row was filtered by the canonical DB query; this is a defensive fallback.
        return new GlobalSearchMatch(GlobalSearchMatchKind.SUBSTRING,
            source.searchFields().get(0));
    }

    private Object readPath(T entity, String path) {
        return ColumnPath.resolve(entityClass, path).getValue(entity);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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
