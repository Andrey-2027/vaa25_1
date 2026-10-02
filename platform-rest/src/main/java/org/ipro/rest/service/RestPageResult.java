package org.ipro.rest.service;

import java.util.List;
import java.util.Map;

/**
 * Immutable scalar result of REST list operation (F-REST-READ-3 §6.2).
 */
public record RestPageResult(List<Map<String, Object>> content,
                             int page,
                             int size,
                             long totalElements,
                             int totalPages) {

    public RestPageResult {
        content = List.copyOf(content == null ? List.of() : content);
    }
}
