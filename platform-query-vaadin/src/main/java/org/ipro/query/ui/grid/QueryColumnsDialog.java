package org.ipro.query.ui.grid;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.dnd.GridDropMode;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.ListDataProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * «Колонки…» (UX-12): видимость, порядок перетаскиванием, заголовок и ширина. Рядом с заголовком
 * показано поле запроса, из которого берётся колонка. «По запросу» возвращает заголовки, порядок и
 * ширины из запроса.
 */
final class QueryColumnsDialog extends Dialog {
    private final QueryGridPanel panel;
    private final List<Row> rows = new ArrayList<>();
    private final ListDataProvider<Row> provider = new ListDataProvider<>(rows);
    private Row dragged;

    QueryColumnsDialog(QueryGridPanel panel) {
        this.panel = panel;
        setHeaderTitle("Колонки");
        setWidth("640px");
        for (QueryGridPanel.ColumnView view : panel.columnViews()) {
            rows.add(new Row(view.alias(), view.queryHeader(), view.title().equals(view.queryHeader()) ? "" : view.title(),
                    view.width(), view.visible()));
        }

        Grid<Row> grid = new Grid<>();
        grid.setItems(provider);
        grid.setAllRowsVisible(rows.size() <= 12);
        grid.setHeight(rows.size() <= 12 ? null : "420px");
        grid.addComponentColumn(row -> {
            Checkbox visible = new Checkbox(row.visible);
            visible.addValueChangeListener(event -> row.visible = event.getValue());
            visible.getElement().setProperty("title", "Показывать колонку");
            return visible;
        }).setHeader("Показ").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(row -> row.alias).setHeader("Поле запроса").setAutoWidth(true);
        grid.addComponentColumn(row -> {
            TextField title = new TextField();
            title.setPlaceholder(row.queryHeader);
            title.setValue(row.title);
            title.setWidthFull();
            title.addValueChangeListener(event -> row.title = event.getValue());
            return title;
        }).setHeader("Заголовок").setFlexGrow(1);
        grid.addComponentColumn(row -> {
            IntegerField width = new IntegerField();
            width.setPlaceholder("авто");
            width.setMin(40);
            width.setMax(2000);
            width.setValue(row.width);
            width.setWidth("90px");
            width.addValueChangeListener(event -> row.width = event.getValue());
            return width;
        }).setHeader("Ширина").setAutoWidth(true).setFlexGrow(0);

        grid.setRowsDraggable(true);
        grid.setDropMode(GridDropMode.BETWEEN);
        grid.addDragStartListener(event -> dragged = event.getDraggedItems().getFirst());
        grid.addDragEndListener(event -> dragged = null);
        grid.addDropListener(event -> {
            Row target = event.getDropTargetItem().orElse(null);
            if (dragged == null || target == null || dragged == target) return;
            rows.remove(dragged);
            int index = rows.indexOf(target);
            rows.add(event.getDropLocation() == com.vaadin.flow.component.grid.dnd.GridDropLocation.BELOW
                    ? index + 1 : index, dragged);
            provider.refreshAll();
        });

        Span hint = new Span("Порядок меняется перетаскиванием строк. Пустой заголовок — как в запросе.");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
        add(new VerticalLayout(hint, grid));

        Button byQuery = new Button("По запросу", event -> resetToQuery());
        Button apply = new Button("Применить", event -> apply());
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Отмена", event -> close());
        getFooter().add(byQuery, new HorizontalLayout(cancel, apply));
    }

    /** Список колонок в порядке запроса: заголовки, видимость и ширины как у запроса. */
    private void resetToQuery() {
        List<String> order = panel.queryColumnOrder();
        for (Row row : rows) {
            row.title = "";
            row.width = null;
            row.visible = true;
        }
        rows.sort(java.util.Comparator.comparingInt(row -> order.indexOf(row.alias)));
        provider.refreshAll();
    }

    private void apply() {
        List<QueryGridPanel.ColumnEdit> edits = new ArrayList<>();
        for (Row row : rows) {
            String title = row.title == null || row.title.isBlank() ? null : row.title.strip();
            edits.add(new QueryGridPanel.ColumnEdit(row.alias, title, row.width, row.visible));
        }
        if (edits.stream().noneMatch(QueryGridPanel.ColumnEdit::visible)) {
            com.vaadin.flow.component.notification.Notification.show("Должна остаться хотя бы одна колонка");
            return;
        }
        panel.applyColumnEdits(edits);
        close();
    }

    private static final class Row {
        final String alias;
        final String queryHeader;
        String title;
        Integer width;
        boolean visible;

        Row(String alias, String queryHeader, String title, Integer width, boolean visible) {
            this.alias = alias;
            this.queryHeader = queryHeader;
            this.title = title;
            this.width = width;
            this.visible = visible;
        }
    }
}
