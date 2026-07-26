package org.ip.form.builder.layout;

import java.util.List;

/** Набор вкладок. Табличные части (@TableSections) сюда не входят — они, как и раньше, всегда
 * подключаются автоматически после основного layout'а, см. TableSectionFactory. */
public record TabSheetNode(List<TabDefinition> tabs) implements LayoutNode {
    public TabSheetNode {
        tabs = List.copyOf(tabs);
    }
}
