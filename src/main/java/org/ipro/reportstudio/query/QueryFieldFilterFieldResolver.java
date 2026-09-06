package org.ipro.reportstudio.query;

import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterFieldResolver;
import org.ipro.reportstudio.data.QueryField;

import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Адаптирует текущую схему SELECT отчёта к общему визуальному фильтру.
 * В v1 разрешены только скалярные поля результата; entity-ссылки исключаются.
 */
public final class QueryFieldFilterFieldResolver implements FilterFieldResolver {
    private final List<ResolvedFilterField> fields;

    public QueryFieldFilterFieldResolver(List<QueryField> schema) {
        this.fields = (schema == null ? List.<QueryField>of() : schema).stream()
                .filter(Objects::nonNull)
                .filter(QueryFieldFilterFieldResolver::isSupported)
                .map(field -> new ResolvedFilterField(field.name(), field.caption(), field.javaType(),
                        dataTypeOf(field.javaType()), true))
                .toList();
    }

    @Override
    public ResolvedFilterField resolve(String path) {
        return fields.stream()
                .filter(field -> Objects.equals(field.path(), path))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Поле отчёта не найдено или недоступно для визуальной фильтрации: " + path));
    }

    @Override
    public List<ResolvedFilterField> fields() {
        return fields;
    }

    @Override
    public List<?> valueOptions(ResolvedFilterField field) {
        if (field != null && field.dataType() == FilterDataType.ENUM
                && field.javaType().isEnum()) {
            return Arrays.asList(field.javaType().getEnumConstants());
        }
        return List.of();
    }

    private static boolean isSupported(QueryField field) {
        Class<?> type = field.javaType();
        return type != null
                && type != Object.class
                && !field.name().startsWith("__");
    }

    private static FilterDataType dataTypeOf(Class<?> type) {
        if (type.isEnum()) return FilterDataType.ENUM;
        if (Number.class.isAssignableFrom(type)
                || type == int.class || type == long.class || type == short.class
                || type == byte.class || type == double.class || type == float.class) {
            return FilterDataType.NUMBER;
        }
        if (type == boolean.class || type == Boolean.class) return FilterDataType.BOOLEAN;
        if (type == java.time.LocalDate.class || type == java.util.Date.class
                || Temporal.class.isAssignableFrom(type)) return FilterDataType.DATE;
        return FilterDataType.TEXT;
    }
}
