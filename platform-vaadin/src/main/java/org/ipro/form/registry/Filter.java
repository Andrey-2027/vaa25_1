package org.ipro.form.registry;

import org.ipro.metadata.ColumnPath;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Typed opening constraint. The path and the value type are checked before opening. */
public record Filter<V>(String path, V value) {
    public Filter {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Opening filter path is required");
        Objects.requireNonNull(value, "Opening filter value is required");
    }
    public static <V> Filter<V> eq(String path, V value) { return new Filter<>(path, value); }
    public static Map<String, Object> values(Class<?> entityClass, Filter<?>... filters) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (var filter : filters) {
            var type = org.springframework.util.ClassUtils.resolvePrimitiveIfNecessary(
                ColumnPath.resolve(entityClass, filter.path()).getJavaType());
            if (!type.isInstance(filter.value())) throw new IllegalArgumentException(
                entityClass.getName() + ": opening filter '" + filter.path() + "' expects " + type.getName());
            if (values.putIfAbsent(filter.path(), filter.value()) != null)
                throw new IllegalArgumentException("Duplicate opening filter '" + filter.path() + "'");
        }
        return Map.copyOf(values);
    }
}
