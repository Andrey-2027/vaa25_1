package org.ipro.data;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable lookup read request canonical executor'а (C4.1): автокомплит и форма выбора.
 *
 * @param type            entity type
 * @param searchFields    Java-поля поиска; невалидные и неподходящие по типу пропускаются
 * @param term            искомая подстрока; blank → все записи в пределах {@code limit}
 * @param limit           максимум записей
 * @param additionalPaths дополнительные JPA-пути
 */
public record LookupRead<T>(Class<T> type,
                            List<String> searchFields,
                            String term,
                            int limit,
                            Collection<String> additionalPaths) {

    public LookupRead {
        Objects.requireNonNull(type, "type must not be null");
        searchFields = List.copyOf(searchFields == null ? List.<String>of() : searchFields);
        additionalPaths = List.copyOf(additionalPaths == null ? List.<String>of() : additionalPaths);
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
    }

    public static <T> LookupRead<T> of(Class<T> type, List<String> searchFields, String term, int limit) {
        return new LookupRead<>(type, searchFields, term, limit, List.of());
    }
}
