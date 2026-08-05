package org.ip.views.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.ipro.telemetry.api.TraceService;
import org.ipro.telemetry.api.UserContext;
import org.ipro.telemetry.core.JournalQueryService;
import org.ipro.telemetry.core.JournalQueryService.AggRow;
import org.ipro.telemetry.core.JournalQueryService.EventFilter;
import org.ipro.telemetry.core.JournalQueryService.EventRow;
import org.ipro.telemetry.core.JournalQueryService.SinkHealth;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Подсистема «Администрирование» (этап 9), доступ ADMIN. Вкладки:
 * «Журнал» — события operation_log с фильтрами и drill-down дерева из
 * payload; «Агрегаты» — сводка perf_stats; «Трассировка» — управление
 * L2-окном + состояние async-writer'а (самонаблюдение).
 */
@SpringComponent
@Scope("prototype")
public class AdminView extends VerticalLayout {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private final JournalQueryService journal;
    private final TraceService traceService;

    private final Grid<EventRow> eventGrid = new Grid<>(EventRow.class, false);
    private final Grid<AggRow> aggGrid = new Grid<>(AggRow.class, false);

    private final VerticalLayout journalTab = new VerticalLayout();
    private final VerticalLayout aggregatesTab = new VerticalLayout();
    private final VerticalLayout traceTab = new VerticalLayout();

    private final DatePicker eventFrom = new DatePicker("Начиная с");
    private final DatePicker eventTo = new DatePicker("По");
    private final ComboBox<String> eventType = new ComboBox<>("Тип события");
    private final TextField userIdFilter = new TextField("Пользователь");
    private final TextField operationFilter = new TextField("Операция");
    private final TextField entityFilter = new TextField("Сущность");
    private final TextField minDurationFilter = new TextField("Мин. длительность, мс");
    private final Checkbox n1Only = new Checkbox("Только N+1");

    private final ComboBox<String> aggScope = new ComboBox<>("Область");
    private final Button traceButton = new Button();
    private final ComboBox<Integer> traceMinutes = new ComboBox<>();
    private final Span healthLabel = new Span();

    public AdminView(@Autowired JournalQueryService journal,
                     @Autowired Optional<TraceService> traceServiceOpt) {
        this.journal = journal;
        this.traceService = traceServiceOpt.orElse(null);
        setSizeFull();
        setPadding(true);
        setSpacing(true);
    }

    @Override
    protected void onAttach(com.vaadin.flow.component.AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (isAdmin()) {
            buildUi();
        } else {
            add(new H3("Доступно только администратору"));
        }
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }

    private void buildUi() {
        add(new H3("Администрирование — журнал телеметрии"));

        Tab journalItem = new Tab(new Span("Журнал"), new Icon(VaadinIcon.LIST_SELECT));
        Tab aggregatesItem = new Tab(new Span("Агрегаты"));
        Tab traceItem = new Tab(new Span("Трассировка"), new Icon(VaadinIcon.BUG));
        Tabs tabs = new Tabs(journalItem, aggregatesItem, traceItem);
        add(tabs);

        buildJournalTab();
        buildAggregatesTab();
        buildTraceTab();

        add(journalTab);

        tabs.addSelectedChangeListener(e -> {
            if (e.getSelectedTab() == aggregatesItem) {
                show(aggregatesTab);
            } else if (e.getSelectedTab() == traceItem) {
                show(traceTab);
                refreshHealth();
            } else {
                show(journalTab);
            }
        });
    }

    private void show(VerticalLayout active) {
        journalTab.setVisible(false);
        aggregatesTab.setVisible(false);
        traceTab.setVisible(false);
        active.setVisible(true);
    }

    // ------------------------------------------------------------ журнал

    private void buildJournalTab() {
        eventType.setItems("PERF_METHOD", "PERF_SQL", "ACTION", "SECURITY",
                "ERROR", "APP", "TRACE");
        eventType.setPlaceholder("Любой тип");
        minDurationFilter.setPlaceholder("напр. 200");

        HorizontalLayout filters = new HorizontalLayout(eventFrom, eventTo, eventType,
                userIdFilter, operationFilter, entityFilter, minDurationFilter, n1Only);
        filters.setAlignItems(Alignment.END);
        filters.setSpacing(true);
        filters.setWrap(true);

        Button apply = new Button("Применить", new Icon(VaadinIcon.REFRESH),
                e -> refreshEvents());
        Button payload = new Button("Дерево операции", new Icon(VaadinIcon.LIST),
                e -> openSelectedPayload());
        payload.setEnabled(false);

        eventGrid.setSelectionMode(Grid.SelectionMode.SINGLE);
        eventGrid.addSelectionListener(e ->
                payload.setEnabled(!e.getFirstSelectedItem().isEmpty()));
        eventGrid.addItemDoubleClickListener(e -> openPayloadDialog(e.getItem().id()));
        configureEventColumns();

        HorizontalLayout actions = new HorizontalLayout(apply, payload);

        journalTab.setSpacing(true);
        journalTab.setSizeFull();
        journalTab.add(filters, actions, eventGrid);
        journalTab.setFlexGrow(1, eventGrid);

        refreshEvents();
    }

    private void configureEventColumns() {
        eventGrid.addColumn(r -> r.startedAt() == null ? "" : TIME.format(
                r.startedAt().atZone(ZoneId.systemDefault())))
                .setHeader("Время");
        eventGrid.addColumn(EventRow::eventType).setHeader("Тип");
        eventGrid.addColumn(EventRow::level).setHeader("Уровень");
        eventGrid.addColumn(EventRow::userId).setHeader("Пользователь");
        eventGrid.addColumn(EventRow::operation).setHeader("Операция").setAutoWidth(true);
        eventGrid.addColumn(r -> r.entity() == null ? "" : r.entity()
                + (r.entityId() != null ? " #" + r.entityId() : "")).setHeader("Сущность");
        eventGrid.addColumn(EventRow::durationMs).setHeader("Длит., мс");
        eventGrid.addColumn(EventRow::sqlCount).setHeader("SQL");
        eventGrid.addColumn(EventRow::sqlTotalMs).setHeader("SQL, мс");
        eventGrid.addColumn(r -> r.n1() ? "N+1" : "").setHeader("N+1");
        eventGrid.addColumn(r -> trace(r.traceId())).setHeader("Trace ID");
        eventGrid.addColumn(EventRow::errorMessage).setHeader("Ошибка").setAutoWidth(true);
    }

    private void refreshEvents() {
        ZoneId zone = ZoneId.systemDefault();
        EventFilter filter = new EventFilter(
                blankToNull(eventType.getValue()),
                blankToNull(userIdFilter.getValue()),
                blankToNull(operationFilter.getValue()),
                blankToNull(entityFilter.getValue()),
                dateToInstant(eventFrom.getValue(), zone, true),
                dateToInstant(eventTo.getValue(), zone, false),
                parseLong(minDurationFilter.getValue()),
                n1Only.getValue(),
                500);
        eventGrid.setItems(journal.queryEvents(filter));
    }

    private void openSelectedPayload() {
        eventGrid.getSelectionModel().getFirstSelectedItem()
                .ifPresent(row -> openPayloadDialog(row.id()));
    }

    private void openPayloadDialog(long id) {
        String payload = journal.payloadById(id);
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Дерево операции (id=" + id + ")");
        dialog.setWidth("950px");
        dialog.setHeight("620px");
        if (payload == null || payload.isBlank()) {
            dialog.add(new Span("payload отсутствует"));
        } else {
            dialog.add(PayloadTreeView.build(payload));
        }
        dialog.open();
    }

    // ---------------------------------------------------------- агрегаты

    private void buildAggregatesTab() {
        aggScope.setItems("Все", "method", "sql");
        aggScope.setValue("method");

        Button refresh = new Button("Обновить", new Icon(VaadinIcon.REFRESH),
                e -> refreshAggregates());

        HorizontalLayout top = new HorizontalLayout(aggScope, refresh);
        top.setAlignItems(Alignment.END);

        aggGrid.addColumn(AggRow::statKey).setHeader("Ключ").setAutoWidth(true);
        aggGrid.addColumn(r -> r.windowStart() == null ? "" : TIME.format(
                r.windowStart().atZone(ZoneId.systemDefault()))).setHeader("Окно");
        aggGrid.addColumn(AggRow::count).setHeader("Вызовов");
        aggGrid.addColumn(r -> round1(r.totalMs())).setHeader("Всего, мс");
        aggGrid.addColumn(r -> round1(r.avgMs())).setHeader("Среднее, мс");
        aggGrid.addColumn(r -> round1(r.maxMs())).setHeader("Максимум, мс");
        aggGrid.addColumn(r -> round1(r.p95Ms())).setHeader("P95, мс");

        aggregatesTab.setSpacing(true);
        aggregatesTab.setSizeFull();
        aggregatesTab.add(top, aggGrid);
        aggregatesTab.setFlexGrow(1, aggGrid);

        refreshAggregates();
    }

    private void refreshAggregates() {
        String scope = aggScope.getValue();
        aggGrid.setItems(journal.aggregates(
                "Все".equals(scope) ? null : scope, null, 300));
    }

    // ------------------------------------------------------- трассировка

    private void buildTraceTab() {
        Span warning = new Span("Внимание: трассировка фиксирует каждый сервисный "
                + "вызов и каждый SQL и влияет на производительность. Окно "
                + "автоматически отключается по истечении выбранных минут.");
        warning.getStyle().set("color", "var(--lumo-error-text-color)");

        traceMinutes.setLabel("Длительность окна");
        traceMinutes.setItems(2, 5, 10);
        traceMinutes.setValue(2);
        traceButton.addClickListener(e -> toggleTrace());
        refreshTraceButton();

        Button healthRefresh = new Button("Обновить", new Icon(VaadinIcon.REFRESH),
                e -> refreshHealth());
        Span healthTitle = new Span("Состояние записи (self-observation)");
        healthTitle.addClassNames("font-bold");

        healthLabel.setWidthFull();
        healthLabel.getStyle().set("white-space", "pre-wrap");

        traceTab.setSpacing(true);
        traceTab.add(warning,
                new HorizontalLayout(traceButton, traceMinutes),
                healthTitle,
                healthRefresh,
                healthLabel);
        refreshHealth();
    }

    private void toggleTrace() {
        if (traceService == null) {
            Notification.show("Телеметрия выключена (ipro.telemetry.enabled=false)");
            return;
        }
        String user = UserContext.defaultInstance().currentUsername();
        if (traceService.isTraceActive(user)) {
            traceService.stopTrace(user);
            Notification.show("Трассировка отключена для " + user, 3000,
                    Notification.Position.MIDDLE);
        } else {
            int minutes = traceMinutes.getValue() == null ? 2 : traceMinutes.getValue();
            traceService.startTrace(user, minutes);
            Notification.show("Трассировка включена на " + minutes + " мин для " + user,
                    3000, Notification.Position.MIDDLE);
        }
        refreshTraceButton();
    }

    private void refreshTraceButton() {
        if (traceService == null) {
            traceButton.setText("Телеметрия выключена");
            traceButton.setEnabled(false);
            return;
        }
        String user = UserContext.defaultInstance().currentUsername();
        if (traceService.isTraceActive(user)) {
            traceButton.setText("Отключить трассировку (" + user + ")");
            traceButton.setIcon(new Icon(VaadinIcon.CLOSE_CIRCLE_O));
            traceButton.addThemeVariants(ButtonVariant.LUMO_ERROR);
        } else {
            traceButton.setText("Включить трассировку");
            traceButton.setIcon(new Icon(VaadinIcon.BUG));
        }
    }

    private void refreshHealth() {
        SinkHealth health = journal.sinkHealth();
        healthLabel.setText(
                "writer: " + (health.active() ? "активен" : "выключен/noop")
                + "\nочередь: " + health.queueSize()
                + "\nзаписано событий: " + health.writtenEvents()
                + "\nзаписано агрегатов: " + health.writtenStats()
                + "\nпотеряно (drop): " + health.dropped()
                + "\nупавших батчей: " + health.failedBatches()
                + (health.lastError() != null ? "\nпоследняя ошибка: " + health.lastError() : ""));
    }

    // ----------------------------------------------------------- helpers

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant dateToInstant(LocalDate date, ZoneId zone, boolean startOfDay) {
        if (date == null) {
            return null;
        }
        return startOfDay
                ? date.atStartOfDay(zone).toInstant()
                : date.plusDays(1).atStartOfDay(zone).toInstant();
    }

    private static String trace(String traceId) {
        return traceId != null && traceId.length() > 12
                ? traceId.substring(0, 12) + "…"
                : traceId;
    }

    private static String round1(double value) {
        return String.valueOf(Math.round(value * 10.0) / 10.0);
    }
}
