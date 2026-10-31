package org.ipro.design;

import java.util.Objects;
import java.util.UUID;

public record FieldDraft(UUID id, String name, String label, String type, DraftText columnName,
                         boolean serverRequired, UiRequired uiRequired, ConstraintDraft constraints,
                         ReferenceDraft reference, FieldApplications applications) {
    public enum UiRequired { AUTO, REQUIRED, OPTIONAL }
    public FieldDraft {
        Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(label);
        Objects.requireNonNull(type); Objects.requireNonNull(columnName); Objects.requireNonNull(uiRequired);
        Objects.requireNonNull(constraints); Objects.requireNonNull(applications);
    }
    public FieldDraft withName(String value) { return new FieldDraft(id, value, label, type, columnName, serverRequired, uiRequired, constraints, reference, applications); }
    /** Type changes deliberately retain incompatible input for the validator. */
    public FieldDraft withType(String value) { return new FieldDraft(id, name, label, value, columnName, serverRequired, uiRequired, constraints, reference, applications); }
}
