package org.ipro.design;

import java.util.Objects;

/** Raw owner capability input, including incomplete and semantically invalid values. */
public record OwnerDraft(String targetTypeId, String fieldName, DraftText columnName, String label) {
    public OwnerDraft {
        Objects.requireNonNull(targetTypeId); Objects.requireNonNull(fieldName);
        Objects.requireNonNull(columnName); Objects.requireNonNull(label);
    }
}
