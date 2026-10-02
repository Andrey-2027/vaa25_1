package org.ipro.rest.catalog;

import org.ipro.rest.api.RestResourceDefinition;

import java.util.Objects;

/** Internal pairing of the declared wire field and its validated persistent path. */
public record ResolvedRestField(RestResourceDefinition.Field declaration, ResolvedRestPath source) {
    public ResolvedRestField {
        Objects.requireNonNull(declaration, "declaration must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }

    public String name() { return declaration.name(); }
}
