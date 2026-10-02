package org.ipro.rest.service;

import org.ipro.rest.api.RestSortDirection;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Normalized internal query for REST list operation (F-REST-READ-3 §6.1).
 */
public record RestListQuery(String resource,
                            int major,
                            List<String> fields,
                            Map<String, Object> filters,
                            String sortBy,
                            RestSortDirection sortDirection,
                            int page,
                            int size) {

    public RestListQuery {
        Objects.requireNonNull(resource, "resource must not be null");
        fields = fields == null ? List.of() : List.copyOf(fields);
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }
}
