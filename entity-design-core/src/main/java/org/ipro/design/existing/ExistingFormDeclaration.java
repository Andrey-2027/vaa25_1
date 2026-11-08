package org.ipro.design.existing;

import java.util.Objects;

/** One source declaration, including named variants that do not define the entity's default columns. */
public record ExistingFormDeclaration(Kind kind, String variant, ExistingFormColumns columns) {
    public enum Kind { LIST, SELECTION }
    public ExistingFormDeclaration {
        Objects.requireNonNull(kind); Objects.requireNonNull(variant); Objects.requireNonNull(columns);
    }
}
