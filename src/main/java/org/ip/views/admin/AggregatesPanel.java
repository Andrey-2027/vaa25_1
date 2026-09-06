package org.ip.views.admin;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import org.ipro.telemetry.core.JournalQueryService;
import org.ipro.telemetry.core.JournalQueryService.AggRow;

/**
 * Вкладка «Агрегаты»: сводка perf_stats (L0) — перенос из прежнего
 * AdminView без изменения логики. Графики поверх этих данных — следующий
 * шаг (вне текущей итерации).
 */
final class AggregatesPanel extends VerticalLayout {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private final JournalQueryService journal;
    private final Grid<AggRow> aggGrid = new Grid<>(AggRow.class, false);
    private final ComboBox<String> scope = new ComboBox<>("Область");

    AggregatesPanel(JournalQueryService journal) {
        this.journal = journal;
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        scope.setItems("Все", "method", "sql");
        scope.setValue("method");

        Button refresh = new Button("Обновить", new Icon(VaadinIcon.REFRESH), e -> refresh());

        HorizontalLayout top = new HorizontalLayout(scope, refresh);
        top.setAlignItems(FlexComponent.Alignment.END);

        aggGrid.addColumn(AggRow::statKey).setHeader("Ключ").setAutoWidth(true);
        aggGrid.addColumn(r -> r.windowStart() == null ? "" : TIME.format(
                r.windowStart().atZone(ZoneId.systemDefault()))).setHeader("Окно");
        aggGrid.addColumn(AggRow::count).setHeader("Вызовов");
        aggGrid.addColumn(r -> round1(r.totalMs())).setHeader("Всего, мс");
        aggGrid.addColumn(r -> round1(r.avgMs())).setHeader("Среднее, мс");
        aggGrid.addColumn(r -> round1(r.maxMs())).setHeader("Максимум, мс");
        aggGrid.addColumn(r -> round1(r.p95Ms())).setHeader("P95, мс");

        add(top, aggGrid);
        setFlexGrow(1, aggGrid);

        refresh();
    }

    private void refresh() {
        String value = scope.getValue();
        try {
            aggGrid.setItems(journal.aggregates(
                    "Все".equals(value) ? null : value, null, 300));
        } catch (RuntimeException e) {
            aggGrid.setItems(java.util.List.of());
            com.vaadin.flow.component.notification.Notification.show(
                    "Ошибка загрузки агрегатов: " + e.getMessage(), 5000,
                    com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
        }
    }

    private static String round1(double value) {
        return String.valueOf(Math.round(value * 10.0) / 10.0);
    }
}
