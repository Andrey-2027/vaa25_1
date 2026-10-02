package org.ipro.rest.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable scalar result of REST detail operation (F-REST-READ-3 В§6.2).
 */
public record RestDetailResult(Map<String, Object> data) {

    public RestDetailResult {
        Objects.requireNonNull(data, "data must not be null");
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}