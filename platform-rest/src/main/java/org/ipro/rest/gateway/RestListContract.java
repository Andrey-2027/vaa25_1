package org.ipro.rest.gateway;

import org.ipro.rest.api.RestSortDirection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable LIST contract for one published {@code (resource, major)} pair (F-REST-READ-4 §3.1).
 *
 * <p>{@code defaultSortAlias} and {@code defaultSortDirection} are {@code null} when no default
 * sort is declared. {@code effectiveMaxPageSize} is {@code min(resource max, platform max)}.</p>
 */
public record RestListContract(
    String resource, int major,
    List<RestFieldFact> maximumFields,
    List<RestFieldFact> defaultFields,
    Map<String, RestFilterFact> filters,
    List<String> sortAliases,
    String defaultSortAlias,
    RestSortDirection defaultSortDirection,
    int defaultPageSize,
    int effectiveMaxPageSize,
    int maxWindow) {

    public RestListContract {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(maximumFields, "maximumFields must not be null");
        Objects.requireNonNull(defaultFields, "defaultFields must not be null");
        Objects.requireNonNull(filters, "filters must not be null");
        Objects.requireNonNull(sortAliases, "sortAliases must not be null");
        maximumFields = List.copyOf(maximumFields);
        defaultFields = List.copyOf(defaultFields);
        filters = Collections.unmodifiableMap(new LinkedHashMap<>(filters));
        sortAliases = List.copyOf(sortAliases);
    }
}
