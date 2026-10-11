package org.ipro.rest.gateway;

import java.util.List;
import java.util.Objects;

/**
 * Immutable DETAIL contract for one published {@code (resource, major)} pair (F-REST-READ-4 §3.1).
 */
public record RestDetailContract(
    String resource, int major,
    List<RestFieldFact> maximumFields,
    List<RestFieldFact> defaultFields,
    RestFieldFact idField) {

    public RestDetailContract {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(maximumFields, "maximumFields must not be null");
        Objects.requireNonNull(defaultFields, "defaultFields must not be null");
        Objects.requireNonNull(idField, "idField must not be null");
        maximumFields = List.copyOf(maximumFields);
        defaultFields = List.copyOf(defaultFields);
    }
}
