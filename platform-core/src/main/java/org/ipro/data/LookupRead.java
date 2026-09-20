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
 * @param limit           максимум записей; больше {@link org.ipro.crud.EntityLookup#MAX_LIMIT}
 *                        — отказ: lookup с огромным лимитом и есть выгрузка таблицы
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
        // D3.5.3: граница контракта, а не пожелание. Единственная точка построения
        // lookup-запроса — значит будущий потребитель не сможет обойти её «своим» лимитом.
        if (limit > org.ipro.crud.EntityLookup.MAX_LIMIT) {
            throw new IllegalArgumentException("limit " + limit + " exceeds "
                + org.ipro.crud.EntityLookup.MAX_LIMIT + " (EntityLookup.MAX_LIMIT): столько"
                + " записей — это полная выборка таблицы, а не lookup. Нужна полная выборка —"
                + " используйте list-read (LIST/Pageable) или отдельный use case с явным"
                + " намерением.");
        }
    }

    public static <T> LookupRead<T> of(Class<T> type, List<String> searchFields, String term, int limit) {
        return new LookupRead<>(type, searchFields, term, limit, List.of());
    }
}
