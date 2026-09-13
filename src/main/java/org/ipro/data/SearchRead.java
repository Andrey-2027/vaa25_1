package org.ipro.data;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable поисковый запрос canonical executor'а (C4.4, ADR-0007 §7).
 *
 * @param type            entity type
 * @param context         намерение поиска (list/lookup/global) — определяет сценарий,
 *                        обязательность терма и telemetry-намерение
 * @param term            пользовательский терм; трактуется литерально
 *                       ({@link SearchTerms})
 * @param searchFields    явные Java-поля поиска; пусто — default resolution
 *                       ({@link SearchFieldResolver})
 * @param pageable        paging; unpaged ограничивается {@link #DEFAULT_PAGE_SIZE}
 * @param additionalPaths дополнительные JPA-пути к плану сценария
 */
public record SearchRead<T>(Class<T> type,
                            SearchContext context,
                            String term,
                            List<String> searchFields,
                            Pageable pageable,
                            Collection<String> additionalPaths) {

    /** Предел выдачи без paging — parity с прежним repository search (было 100). */
    public static final int DEFAULT_PAGE_SIZE = 100;

    public SearchRead {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(context, "context must not be null");
        searchFields = List.copyOf(searchFields == null ? List.<String>of() : searchFields);
        pageable = pageable == null ? defaultPage() : pageable;
        additionalPaths = List.copyOf(additionalPaths == null ? List.<String>of() : additionalPaths);
    }

    /** Первая страница предела по умолчанию. */
    public static Pageable defaultPage() {
        return PageRequest.of(0, DEFAULT_PAGE_SIZE);
    }

    public static <T> SearchRead<T> of(Class<T> type, SearchContext context, String term,
                                       Pageable pageable) {
        return new SearchRead<>(type, context, term, List.of(), pageable, List.of());
    }

    public static <T> SearchRead<T> of(Class<T> type, SearchContext context, String term) {
        return of(type, context, term, defaultPage());
    }

    /** Тот же запрос с явными полями поиска (проверяются, а не пропускаются). */
    public SearchRead<T> withFields(List<String> fields) {
        return new SearchRead<>(type, context, term, fields, pageable, additionalPaths);
    }

    /** Тот же запрос с дополнительными fetch-путями. */
    public SearchRead<T> withPaths(Collection<String> paths) {
        return new SearchRead<>(type, context, term, searchFields, pageable, paths);
    }
}
