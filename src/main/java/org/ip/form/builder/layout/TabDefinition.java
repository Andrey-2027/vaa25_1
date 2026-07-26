package org.ip.form.builder.layout;

import java.util.List;

/** Одна вкладка TabSheet'а: заголовок + дочерние узлы. Не является LayoutNode само по себе —
 * это всегда элемент {@link TabSheetNode#tabs()}. */
public record TabDefinition(String title, List<LayoutNode> children) {
    public TabDefinition {
        children = List.copyOf(children);
    }
}
