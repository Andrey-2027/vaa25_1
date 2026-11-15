package org.ipro.query.ui.grid;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.ipro.filtergrid.FooterAggregateKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * «Итоги…» (UX-12): функция подвала для каждой колонки. Для чисел — сумма, среднее, минимум,
 * максимум; для любой колонки — количество строк; «нет» — без итога. Итоги считаются по всей
 * отфильтрованной выборке.
 */
final class QueryTotalsDialog extends Dialog {
    private static final String NONE = "нет";

    private final QueryGridPanel panel;
    private final List<Row> rows = new ArrayList<>();

    QueryTotalsDialog(QueryGridPanel panel) {
        this.panel = panel;
        setHeaderTitle("Итоги");
        setWidth("460px");
        for (QueryGridPanel.TotalOption option : panel.totalOptions()) rows.add(new Row(option));

        Grid<Row> grid = new Grid<>();
        grid.setItems(rows);
        grid.setAllRowsVisible(rows.size() <= 12);
        grid.addColumn(row -> row.option.title()).setHeader("Колонка").setFlexGrow(1);
        grid.addComponentColumn(row -> {
            ComboBox<String> function = new ComboBox<>();
            function.setAllowCustomValue(false);
            function.setItems(functions(row.option));
            function.setValue(row.choice);
            function.setWidth("11em");
            function.addValueChangeListener(event -> row.choice = event.getValue() == null ? NONE : event.getValue());
            return function;
        }).setHeader("Итог в подвале").setAutoWidth(true).setFlexGrow(0);
        add(grid);

        Button apply = new Button("Применить", event -> apply());
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        getFooter().add(new HorizontalLayout(new Button("Отмена", event -> close()), apply));
    }

    private void apply() {
        Map<String, FooterAggregateKind> totals = new LinkedHashMap<>();
        for (Row row : rows) {
            FooterAggregateKind kind = kind(row.choice);
            if (kind != null) totals.put(row.option.alias(), kind);
        }
        panel.applyTotals(totals);
        close();
    }

    private static List<String> functions(QueryGridPanel.TotalOption option) {
        List<String> items = new ArrayList<>(List.of(NONE));
        if (option.numeric()) items.addAll(List.of("сумма", "среднее", "минимум", "максимум"));
        items.add("количество строк");
        return items;
    }

    static FooterAggregateKind kind(String title) {
        return switch (title) {
            case "сумма" -> FooterAggregateKind.SUM;
            case "среднее" -> FooterAggregateKind.AVG;
            case "минимум" -> FooterAggregateKind.MIN;
            case "максимум" -> FooterAggregateKind.MAX;
            case "количество строк" -> FooterAggregateKind.COUNT;
            default -> null;
        };
    }

    static String title(FooterAggregateKind kind) {
        return switch (kind) {
            case SUM -> "сумма";
            case AVG -> "среднее";
            case MIN -> "минимум";
            case MAX -> "максимум";
            case COUNT -> "количество строк";
        };
    }

    private static final class Row {
        final QueryGridPanel.TotalOption option;
        String choice;

        Row(QueryGridPanel.TotalOption option) {
            this.option = option;
            this.choice = option.current() == null ? NONE : title(option.current());
        }
    }
}
