package org.ipro.design.existing;

import java.util.Objects;
import java.util.UUID;
import org.ipro.design.FieldDraft;

/** One supported change of an existing entity. Everything else is refused, never approximated. */
public sealed interface EntityChange permits EntityChange.FieldAdded, EntityChange.LabelChanged {
    /** A new optional field. Its place and members in the file are decided by the patcher. */
    record FieldAdded(FieldDraft field) implements EntityChange {
        public FieldAdded { Objects.requireNonNull(field); }
    }
    /** A new label for a field that is already in the file; {@code name} is the field name. */
    record LabelChanged(UUID fieldId, String name, String from, String to) implements EntityChange {
        public LabelChanged {
            Objects.requireNonNull(fieldId); Objects.requireNonNull(name); Objects.requireNonNull(from); Objects.requireNonNull(to);
        }
    }
}
