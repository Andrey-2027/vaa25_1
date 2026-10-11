package org.ipro.rest.gateway;

import java.util.Objects;

/**
 * Immutable wire contract of one published {@code (resource, major)} pair (F-REST-READ-4 §3.1).
 */
public record RestReadResourceSchema(String resource, int major,
                                     RestListContract list, RestDetailContract detail) {

    public RestReadResourceSchema {
        Objects.requireNonNull(resource, "resource must not be null");
        if (resource.isBlank()) {
            throw new IllegalArgumentException("resource must not be blank");
        }
        if (major < 1) {
            throw new IllegalArgumentException("major must be positive: " + major);
        }
        Objects.requireNonNull(list, "list must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
    }
}
