package org.ipro.form.builder.layout;

import org.ipro.identity.IdentifiableEntity;
import java.util.Objects;

/** Position of an owned table section; the table itself is attached by TableSectionFactory. */
public record SectionNode(Class<? extends IdentifiableEntity<Long>> rowClass) implements LayoutNode {
    public SectionNode {
        Objects.requireNonNull(rowClass, "rowClass");
    }
}
