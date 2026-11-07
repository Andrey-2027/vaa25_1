package org.ipro.form.builder.layout;

import java.util.Arrays;
import java.util.List;

/** Published, non-hidden fields not explicitly placed anywhere in the layout. */
public record RemainingFieldsNode(List<String> excludedFields) implements LayoutNode {
    public RemainingFieldsNode {
        excludedFields = List.copyOf(excludedFields);
    }

    public RemainingFieldsNode() {
        this(List.of());
    }

    /** Returns a new node; the original layout declaration remains unchanged. */
    public RemainingFieldsNode except(String... fieldNames) {
        var excluded = new java.util.ArrayList<>(excludedFields);
        excluded.addAll(Arrays.asList(fieldNames));
        return new RemainingFieldsNode(excluded);
    }
}
