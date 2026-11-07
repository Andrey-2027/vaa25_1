package org.ipro.design;

import java.util.Objects;
import java.util.UUID;

/** Explicit numeric-reference row scope. A missing field is preserved as incomplete editor input. */
public record RegisterRlsDraft(String dimension, UUID fieldId) {
    public RegisterRlsDraft { Objects.requireNonNull(dimension); }
}
