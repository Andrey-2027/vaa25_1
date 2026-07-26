package org.ip.form.builder.layout;

import java.util.List;

/** Корень дерева layout'а Формы Элемента — список узлов верхнего уровня. */
public record ItemFormLayout(List<LayoutNode> nodes) {
    public ItemFormLayout {
        nodes = List.copyOf(nodes);
    }
}
