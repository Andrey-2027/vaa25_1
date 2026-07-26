package org.ip.form.builder.layout;

import java.util.List;

/** Горизонтальная группа дочерних узлов (например, несколько полей в один ряд). */
public record PanelNode(List<LayoutNode> children) implements LayoutNode {
    public PanelNode {
        children = List.copyOf(children);
    }
}
