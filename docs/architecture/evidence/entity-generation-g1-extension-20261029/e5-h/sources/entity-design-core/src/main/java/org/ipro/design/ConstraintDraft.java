package org.ipro.design;

import java.util.Objects;

public record ConstraintDraft(String length, String precision, String scale) {
    public ConstraintDraft { Objects.requireNonNull(length); Objects.requireNonNull(precision); Objects.requireNonNull(scale); }
    public static ConstraintDraft empty() { return new ConstraintDraft("", "", ""); }
}
