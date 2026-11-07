package org.ipro.form.builder.layout;

import java.util.List;

/** Набор вкладок; SectionNode задаёт место автоматически подключаемой табличной части. */
public record TabSheetNode(List<TabDefinition> tabs) implements LayoutNode {
    public TabSheetNode {
        tabs = List.copyOf(tabs);
    }
}
