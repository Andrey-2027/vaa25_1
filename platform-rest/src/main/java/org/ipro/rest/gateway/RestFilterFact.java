package org.ipro.rest.gateway;

import org.ipro.rest.api.RestFieldType;
import org.ipro.rest.api.RestFilterOperator;

import java.util.Objects;

/**
 * One enabled equality filter as seen by the wire layer (F-REST-READ-4 §3.1):
 * alias, value type and operator; no Java type or path.
 */
public record RestFilterFact(String alias, RestFieldType type, RestFilterOperator operator) {

    public RestFilterFact {
        Objects.requireNonNull(alias, "alias must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(operator, "operator must not be null");
    }
}
