package org.ipro.data;

import org.ipro.filtergrid.util.JpaPathUtil;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.Map;

/**
 * Единый построитель отбора выбора «путь → значение» (Б1). Один и тот же отбор действует в
 * диалоге выбора ({@code SelectionForm}) и в автокомплите ({@link LookupRead}), поэтому
 * правило живёт здесь, а не в слое форм: два канала не могут разойтись.
 *
 * <p>Скаляр — равенство, коллекция — {@code IN}. Пустые ключи и {@code null}-значения
 * пропускаются. Пустой отбор даёт {@code null} (без ограничений).</p>
 */
public final class SelectionFilters {

    private SelectionFilters() {
    }

    public static <T> Specification<T> specificationFor(Map<String, Object> filters) {
        Specification<T> result = null;
        if (filters == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String path = entry.getKey();
            Object value = entry.getValue();
            if (path == null || path.isBlank() || value == null) {
                continue;
            }
            Specification<T> part = value instanceof Collection<?> values
                ? (root, query, cb) -> cb.in(JpaPathUtil.resolve(root, path)).value(values)
                : (root, query, cb) -> cb.equal(JpaPathUtil.resolve(root, path), value);
            result = result == null ? part : Specification.where(result).and(part);
        }
        return result;
    }

    /** Отбор без пустых ключей и значений; неизменяемая копия с сохранением порядка. */
    static Map<String, Object> normalized(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                Object value = entry.getValue() instanceof Collection<?> values
                    ? java.util.List.copyOf(values) : entry.getValue();
                copy.put(entry.getKey(), value);
            }
        }
        return java.util.Collections.unmodifiableMap(copy);
    }
}
