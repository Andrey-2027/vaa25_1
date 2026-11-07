package org.ipro.form.builder;

import org.ipro.filtergrid.PeriodRange;
import org.ipro.filtergrid.util.JpaPathUtil;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.annotation.FieldType;
import org.springframework.data.jpa.domain.Specification;
import java.util.List;
import java.util.Map;

/** Shared list/selection semantics. Fixed opening constraints always use equality. */
public final class ContextFilterConditions {
    private ContextFilterConditions() { }
    public static boolean empty(Object value) {
        return value == null || value instanceof String text && text.isBlank()
                || value instanceof PeriodRange<?> range && range.isEmpty();
    }
    public static boolean prefill(ContextFilterField field, Class<?> type) {
        if (field.path().contains(".") || field.options().condition() != null
                || field.options().operation() == ContextFilterOptions.Operation.PERIOD) return false;
        return field.options().operation() != ContextFilterOptions.Operation.AUTO
                || !List.of(FieldType.TEXT, FieldType.EMAIL, FieldType.PASSWORD)
                    .contains(ColumnPath.resolve(type, field.path()).getResolvedType());
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> Specification<T> specificationFor(Class<?> type, List<ContextFilterField> fields,
                                                       Map<String, Object> values) {
        Specification<T> result = null;
        for (var entry : values.entrySet()) {
            if (empty(entry.getValue())) continue;
            String path = entry.getKey(); Object value = entry.getValue();
            ContextFilterField field = fields.stream().filter(f -> f.path().equals(path)).findFirst().orElse(null);
            Specification<T> part;
            if (field != null && field.options().condition() != null) {
                part = (Specification<T>) field.options().condition().create(value);
            } else if (value instanceof PeriodRange<?> range) {
                part = (root, query, cb) -> {
                    jakarta.persistence.criteria.Expression expression = JpaPathUtil.resolve(root, path);
                    if (range.to() instanceof java.time.LocalDateTime upper
                            && upper.toLocalTime().equals(java.time.LocalTime.MAX)) {
                        var datetime = JpaPathUtil.<java.time.LocalDateTime>resolve(root, path);
                        var beforeNextDay = cb.lessThan(datetime, upper.toLocalDate().plusDays(1).atStartOfDay());
                        return range.from() == null ? beforeNextDay : cb.and(
                            cb.greaterThanOrEqualTo(datetime, (java.time.LocalDateTime) range.from()), beforeNextDay);
                    }
                    if (range.from() == null) return cb.lessThanOrEqualTo(expression, (Comparable) range.to());
                    if (range.to() == null) return cb.greaterThanOrEqualTo(expression, (Comparable) range.from());
                    return cb.between(expression, (Comparable) range.from(), (Comparable) range.to());
                };
            } else if (field != null && field.options().operation() == ContextFilterOptions.Operation.AUTO
                    && List.of(FieldType.TEXT, FieldType.EMAIL, FieldType.PASSWORD)
                        .contains(ColumnPath.resolve(type, path).getResolvedType())) {
                String escaped = value.toString().toLowerCase(java.util.Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                part = (root, query, cb) -> cb.like(cb.lower(JpaPathUtil.resolve(root, path)), "%" + escaped + "%", '\\');
            } else {
                part = org.ipro.data.SelectionFilters.specificationFor(Map.of(path, value));
            }
            result = result == null ? part : result.and(part);
        }
        return result;
    }
}
