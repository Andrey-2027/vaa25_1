package org.ipro.rest.service;

import java.util.List;
import java.util.Objects;

/**
 * Normalized internal query for REST detail operation (F-REST-READ-3 §6.1).
 */
public record RestDetailQuery(String resource,
                              int major,
                              List<String> fields,
                              Object id) {

    public RestDetailQuery {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(id, "id must not be null");
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
