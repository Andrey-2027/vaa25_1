package org.ipro.data;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable detail read request canonical executor'а для REST API (F-REST-READ-3).
 *
 * @param type            entity type
 * @param id              идентификатор
 * @param fixedFetchPaths фиксированный набор JPA-путей для построения независимого от UI графа
 */
public record ApiDetailRead<T>(Class<T> type,
                               Object id,
                               Collection<String> fixedFetchPaths) {

    public ApiDetailRead {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(id, "id must not be null");
        if (fixedFetchPaths == null || fixedFetchPaths.isEmpty()) {
            throw new IllegalArgumentException("fixedFetchPaths must not be null or empty for API detail read");
        }
        fixedFetchPaths = List.copyOf(fixedFetchPaths);
    }

    public static <T> ApiDetailRead<T> of(Class<T> type, Object id, Collection<String> fixedFetchPaths) {
        return new ApiDetailRead<>(type, id, fixedFetchPaths);
    }
}
