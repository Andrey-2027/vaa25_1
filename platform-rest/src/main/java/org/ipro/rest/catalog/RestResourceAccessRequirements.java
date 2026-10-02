package org.ipro.rest.catalog;

import java.util.List;
import java.util.Objects;

/** Metadata requirements only; it contains no grants or authorization decision. */
public record RestResourceAccessRequirements(RestResourceKey key, Class<?> entityType,
                                             RestReadOperation operation,
                                             List<RestAttributeRequirement> attributes) {
    public RestResourceAccessRequirements {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        attributes = List.copyOf(attributes);
    }
}
