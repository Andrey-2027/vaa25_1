package org.ipro.form.builder.layout;

/** Immutable per-form presentation overrides around a node of the closed layout vocabulary. */
public record ElementNode(String id, LayoutNode content, boolean visible, boolean readOnly,
                          String width, String selectionVariant, java.util.List<String> lookupPaths,
                          java.util.List<String> readPaths, FieldRelations relations) implements LayoutNode {
    public ElementNode(String id, LayoutNode content, boolean visible, boolean readOnly,
                       String width, String selectionVariant, java.util.List<String> lookupPaths,
                       java.util.List<String> readPaths) {
        this(id, content, visible, readOnly, width, selectionVariant, lookupPaths, readPaths, FieldRelations.NONE);
    }
    public ElementNode(String id, LayoutNode content, boolean visible, boolean readOnly,
                       String width, String selectionVariant) {
        this(id, content, visible, readOnly, width, selectionVariant, java.util.List.of(), java.util.List.of());
    }
    public ElementNode {
        lookupPaths = java.util.List.copyOf(lookupPaths);
        readPaths = java.util.List.copyOf(readPaths);
        java.util.Objects.requireNonNull(relations);
    }
}
