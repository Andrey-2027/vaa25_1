package org.ipro.rest.catalog;

import java.util.Objects;

/** One alias/use-specific persistent path that a later security gate must evaluate. */
public record RestAttributeRequirement(String publicName, RestPathUsage usage,
                                      ResolvedRestPath path) {
    public RestAttributeRequirement {
        Objects.requireNonNull(publicName, "publicName must not be null");
        Objects.requireNonNull(usage, "usage must not be null");
        Objects.requireNonNull(path, "path must not be null");
    }
}
