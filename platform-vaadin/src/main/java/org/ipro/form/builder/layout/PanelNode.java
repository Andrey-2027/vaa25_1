package org.ipro.form.builder.layout;

import java.util.List;

/** Горизонтальная группа дочерних узлов (например, несколько полей в один ряд). */
public record PanelNode(String id, String title, List<LayoutNode> children) implements LayoutNode {
    public PanelNode {
        if (id != null && id.isBlank()) throw new IllegalArgumentException("Panel id must not be blank");
        children = List.copyOf(children);
    }

    public PanelNode(List<LayoutNode> children) {
        this(null, null, children);
    }

    public PanelNode(String id, List<LayoutNode> children) {
        this(id, null, children);
    }
}
