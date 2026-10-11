package org.ipro.reportstudio.query.catalog;

import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.component.grid.GridVariant;
import java.util.function.Function;

/** Shared tree presentation; selection is owned by the editor or constructor. */
public final class QuerySourceTree<T> extends TreeGrid<T> {
    public void configure(Function<T, String> caption, Function<T, String> name,
                          Function<T, String> category, Function<T, String> dataType) {
        removeAllColumns();
        addHierarchyColumn(caption::apply).setHeader("Наименование").setFlexGrow(1).setResizable(true);
        addColumn(name::apply).setHeader("Имя в запросе").setFlexGrow(1).setResizable(true);
        addColumn(category::apply).setHeader("Тип объекта").setAutoWidth(true).setFlexGrow(0).setResizable(true);
        setTooltipGenerator(node -> dataType.apply(node));
        addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
    }
}
