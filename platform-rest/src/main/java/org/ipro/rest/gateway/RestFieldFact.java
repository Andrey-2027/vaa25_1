package org.ipro.rest.gateway;

import org.ipro.rest.api.RestFieldFormat;
import org.ipro.rest.api.RestFieldType;
import org.ipro.rest.api.RestNullability;

import java.util.Objects;

/**
 * One published scalar field as seen by the wire layer (F-REST-READ-4 §3.1):
 * alias, type, format hint and promised nullability; no Java type or path.
 */
public record RestFieldFact(String alias, RestFieldType type,
                            RestFieldFormat format, RestNullability nullability) {

    public RestFieldFact {
        Objects.requireNonNull(alias, "alias must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(nullability, "nullability must not be null");
    }
}
