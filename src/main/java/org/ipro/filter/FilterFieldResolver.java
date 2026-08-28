package org.ipro.filter;

import java.util.List;

/** Проверяет путь, тип и варианты значений поля для редактора фильтров. */
public interface FilterFieldResolver {
    ResolvedFilterField resolve(String path);

    default List<ResolvedFilterField> fields() {
        return List.of();
    }

    default List<?> valueOptions(ResolvedFilterField field) {
        return List.of();
    }

    record ResolvedFilterField(String path, String label, Class<?> javaType,
                               FilterDataType dataType, boolean filterEnabled) {
    }
}
