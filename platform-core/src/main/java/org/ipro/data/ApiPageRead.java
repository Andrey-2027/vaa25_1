package org.ipro.data;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable paged read request canonical executor'а для REST API (F-REST-READ-3).
 *
 * @param type            entity type
 * @param filter          Specification или {@code null}
 * @param pageable        paging и sort
 * @param fixedFetchPaths фиксированный набор JPA-путей для построения независимого от UI графа
 */
public record ApiPageRead<T>(Class<T> type,
                             Specification<T> filter,
                             Pageable pageable,
                             Collection<String> fixedFetchPaths) {

    public ApiPageRead {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(pageable, "pageable must not be null");
        if (fixedFetchPaths == null || fixedFetchPaths.isEmpty()) {
            throw new IllegalArgumentException("fixedFetchPaths must not be null or empty for API read");
        }
        fixedFetchPaths = List.copyOf(fixedFetchPaths);
    }

    public static <T> ApiPageRead<T> of(Class<T> type, Specification<T> filter,
                                        Pageable pageable, Collection<String> fixedFetchPaths) {
        return new ApiPageRead<>(type, filter, pageable, fixedFetchPaths);
    }
}
