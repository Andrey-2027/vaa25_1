package org.ipro.form.builder;

import java.util.List;

/** A responsive row; labels are inline unless labelsAbove is true. */
public record FilterPanelGroup(String id, boolean labelsAbove, List<ContextFilterField> fields)
        implements FilterPanelElement {
    public FilterPanelGroup {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Filter group ID is required");
        fields = List.copyOf(fields);
        if (fields.isEmpty()) throw new IllegalArgumentException("Filter group must contain fields");
    }
}
