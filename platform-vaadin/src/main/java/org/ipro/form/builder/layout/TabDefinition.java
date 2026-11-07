package org.ipro.form.builder.layout;

import java.util.List;

/** Одна вкладка TabSheet'а: заголовок + дочерние узлы. Не является LayoutNode само по себе —
 * это всегда элемент {@link TabSheetNode#tabs()}. */
public record TabDefinition(String id, String title, List<LayoutNode> children, boolean visible, String width) {
    public TabDefinition {
        if (id != null && id.isBlank()) throw new IllegalArgumentException("Tab id must not be blank");
        children = List.copyOf(children);
    }

    public TabDefinition(String title, List<LayoutNode> children) {
        this(null, title, children, true, null);
    }
    public TabDefinition(String id, String title, List<LayoutNode> children) { this(id, title, children, true, null); }
}
