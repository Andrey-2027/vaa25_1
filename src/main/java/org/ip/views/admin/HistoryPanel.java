package org.ip.views.admin;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.data.value.ValueChangeMode;

import org.ipro.telemetry.core.FieldAuditQueryService;
import org.ipro.telemetry.core.FieldAuditQueryService.ChangeFilter;
import org.ipro.telemetry.core.FieldAuditQueryService.ChangeRow;

/**
 * Вкладка «История изменений»: field-level аудит entity_change_log
 * с drill-down «поле | было | стало» — перенос из прежнего AdminView.
 */
final class HistoryPanel extends VerticalLayout {

    private final FieldAuditQueryService fieldAudit;

    private final Grid<ChangeRow> changeGrid = new Grid<>(ChangeRow.class, false);
    private final TextField entityFilter = new TextField("Сущность");
    private final TextField entityIdFilter = new TextField("ID записи");
    private final TextField userFilter = new TextField("Пользователь");
    private final DatePicker from = new DatePicker("Начиная с");
    private final DatePicker to = new DatePicker("По");

    HistoryPanel(FieldAuditQueryService fieldAudit) {
        this.fieldAudit = fieldAudit;
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        entityFilter.setClearButtonVisible(true);
        entityIdFilter.setClearButtonVisible(true);
        userFilter.setClearButtonVisible(true);

        Button refresh = new Button("Обновить", new Icon(VaadinIcon.REFRESH), e -> refresh());
        Button details = new Button("Подробности", new Icon(VaadinIcon.LIST), e -> openSelected());
        details.setEnabled(false);

        changeGrid.setSelectionMode(Grid.SelectionMode.SINGLE);
        changeGrid.addSelectionListener(e -> details.setEnabled(!e.getFirstSelectedItem().isEmpty()));
        changeGrid.addItemDoubleClickListener(e -> openChangeDialog(e.getItem().id()));

        changeGrid.addColumn(r -> TelemetryUi.formatTime(r.changedAt())).setHeader("Время");
        changeGrid.addColumn(ChangeRow::changeType).setHeader("Тип");
        changeGrid.addColumn(r -> r.entity() + " #" + r.entityId()).setHeader("Сущность")
                .setAutoWidth(true);
        changeGrid.addColumn(ChangeRow::userId).setHeader("Пользователь");
        changeGrid.addColumn(ChangeRow::fieldCount).setHeader("Полей");
        changeGrid.addColumn(r -> TelemetryUi.shortTrace(r.traceId())).setHeader("Trace ID");

        HorizontalLayout filters = new HorizontalLayout(entityFilter, entityIdFilter,
                userFilter, from, to, refresh, details);
        filters.setAlignItems(FlexComponent.Alignment.END);
        filters.setSpacing(true);
        filters.setWrap(true);

        add(filters, changeGrid);
        setFlexGrow(1, changeGrid);

        refresh();
    }

    private void refresh() {
        ZoneId zone = ZoneId.systemDefault();
        ChangeFilter filter = new ChangeFilter(
                blankToNull(entityFilter.getValue()),
                blankToNull(entityIdFilter.getValue()),
                blankToNull(userFilter.getValue()),
                instant(from, zone, true),
                instant(to, zone, false),
                500);
        try {
            changeGrid.setItems(fieldAudit.queryChanges(filter));
        } catch (RuntimeException e) {
            // Vaadin UI-поток может не иметь SecurityContext: показываем ошибку,
            // а не молча пустой грид (queryChanges требует ROLE_ADMIN).
            changeGrid.setItems(List.of());
            com.vaadin.flow.component.notification.Notification.show(
                    "Ошибка загрузки истории: " + e.getMessage(), 5000,
                    com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
        }
    }

    private void openSelected() {
        changeGrid.getSelectionModel().getFirstSelectedItem()
                .ifPresent(row -> openChangeDialog(row.id()));
    }

    private void openChangeDialog(long id) {
        String payload = fieldAudit.payloadById(id);
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Изменение полей (id=" + id + ")");
        dialog.setWidth("800px");
        dialog.setHeight("520px");
        if (payload == null || payload.isBlank()) {
            dialog.add(new Span("payload отсутствует"));
        } else {
            Grid<FieldDiff> grid = new Grid<>(FieldDiff.class, false);
            grid.addColumn(FieldDiff::field).setHeader("Поле").setAutoWidth(true);
            grid.addColumn(FieldDiff::oldValue).setHeader("Было").setWidth("300px");
            grid.addColumn(FieldDiff::newValue).setHeader("Стало").setWidth("300px");
            grid.setItems(parseDiff(payload));
            dialog.add(grid);
        }
        dialog.open();
    }

    /** Один элемент payload: скалярное изменение или сводка табличной части. */
    private record FieldDiff(String field, String oldValue, String newValue) {
    }

    private List<FieldDiff> parseDiff(String payload) {
        try {
            JsonNode root = new ObjectMapper().readTree(payload);
            List<FieldDiff> result = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    String field = text(node.get("field"));
                    if (node.has("added") || node.has("removed") || node.has("changed")) {
                        String summary = "добавлено: " + num(node, "added")
                                + ", удалено: " + num(node, "removed")
                                + ", изменено: " + num(node, "changed");
                        result.add(new FieldDiff(field, "", summary));
                    } else {
                        result.add(new FieldDiff(field, text(node.get("old")), text(node.get("new"))));
                    }
                }
            }
            return result;
        } catch (Exception e) {
            return List.of(new FieldDiff("payload", e.toString(), ""));
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText();
    }

    private static String num(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null ? "0" : String.valueOf(value.asInt());
    }

    private static java.time.Instant instant(DatePicker picker, ZoneId zone, boolean startOfDay) {
        if (picker.getValue() == null) {
            return null;
        }
        return startOfDay
                ? picker.getValue().atStartOfDay(zone).toInstant()
                : picker.getValue().plusDays(1).atStartOfDay(zone).toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
