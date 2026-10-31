package org.ipro.design;

import java.util.Objects;

public record ReferenceDraft(String typeId) {
    public ReferenceDraft { Objects.requireNonNull(typeId); }
}
