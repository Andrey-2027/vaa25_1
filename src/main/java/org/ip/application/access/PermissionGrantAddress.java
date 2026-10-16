package org.ip.application.access;

import java.io.Serializable;
import java.util.Objects;

/** Subject-independent storage coordinate. null valueId is global, not a catalogue row id. */
public record PermissionGrantAddress(PermissionBinding.Authority authority, String key,
                                     Long valueId) implements Serializable {
    public PermissionGrantAddress {
        Objects.requireNonNull(authority, "authority");
        key = PermissionBinding.requireKey(key);
        if (valueId != null && valueId <= 0) throw new IllegalArgumentException("A persisted value id is required");
        if (key.startsWith("ATTR:")) throw new IllegalArgumentException("Field grants are not operative addresses");
        if (authority != PermissionBinding.Authority.RLS && (valueId != null || key.equals("*"))) {
            throw new IllegalArgumentException("C5 grants require an exact global key, never an RLS value/wildcard");
        }
    }
}
