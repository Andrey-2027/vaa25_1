package org.ipro.form.builder.layout;

/** Immutable per-form presentation overrides around a node of the closed layout vocabulary. */
public record ElementNode(String id, LayoutNode content, boolean visible, boolean readOnly,
                          String width, String selectionVariant) implements LayoutNode { }
