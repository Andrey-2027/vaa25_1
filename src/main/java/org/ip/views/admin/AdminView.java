package org.ip.views.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.ipro.telemetry.api.TraceService;
import org.ipro.telemetry.api.UserContext;
import org.ipro.telemetry.core.FieldAuditQueryService;
import org.ipro.telemetry.core.FieldAuditQueryService.ChangeFilter;
import org.ipro.telemetry.core.FieldAuditQueryService.ChangeRow;
import org.ipro.telemetry.core.JournalQueryService;
import org.ipro.telemetry.core.JournalQueryService.AggRow;
import org.ipro.telemetry.core.JournalQueryService.EventFilter;
import org.ipro.telemetry.core.JournalQueryService.EventRow;
import org.ipro.telemetry.core.JournalQueryService.SinkHealth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.ipro.rls.AccessGrant;
import org.ip.service.AccessGrantAdminService;
import org.ip.service.AccessGrantAdminService.GrantFlags;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Подсистема «Администрирование» (этап 9), доступ ADMIN. Вкладки:
 * «Журнал» — события operation_log с фильтрами и drill-down дерева из
 * payload; «Агрегаты» — сводка perf_stats; «Трассировка» — управление
 * L2-окном + состояние async-writer'а (самонаблюдение);
 * «История изменений» (этап 10) — field-level аудит entity_change_log
 * с drill-down «поле | было | стало».
 */
@SpringComponent
@Scope("prototype")
public class AdminView extends VerticalLayout {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private final JournalQueryService journal;
    private final FieldAuditQueryService fieldAudit;
    private final TraceService traceService;
    private final AccessGrantAdminService accessGrantAdminService;
    private final SettingsAdminTab settingsTab;
    private final NumberingAdminTab numberingTab;

    private final Grid<EventRow> eventGrid = new Grid<>(EventRow.class, false);
    private final Grid<AggRow> aggGrid = new Grid<>(AggRow.class, false);
    private final Grid<ChangeRow> changeGrid = new Grid<>(ChangeRow.class, false);
    private final Grid<DimensionValueAccessRow> accessGrid = new Grid<>();

    private final VerticalLayout journalTab = new VerticalLayout();
    private final VerticalLayout aggregatesTab = new VerticalLayout();
    private final VerticalLayout traceTab = new VerticalLayout();
    private final VerticalLayout historyTab = new VerticalLayout();
    private final VerticalLayout accessTab = new VerticalLayout();

    private final ComboBox<String> accessDimension = new ComboBox<>("Измерение");
    private final ComboBox<AccessGrant.SubjectType> accessSubjectType = new ComboBox<>("Тип субъекта");
    private final ComboBox<String> accessSubjectKey = new ComboBox<>("Пользователь/роль");
    private final List<DimensionValueAccessRow> accessRows = new ArrayList<>();
    private final Checkbox singleGrantRead = new Checkbox("");
    private final Checkbox singleGrantUpdate = new Checkbox("");
    private final Checkbox singleGrantDelete = new Checkbox("");
    private HorizontalLayout singleGrantRowLayout;
    private final Button saveAccessButton = new Button("Сохранить", new Icon(VaadinIcon.CHECK), e -> saveAccessMatrix());
    private final Button effectiveRightsButton = new Button("Эффективные права",
            new Icon(VaadinIcon.EYE), e -> openEffectiveRightsDialog());
    private boolean accessSaveInProgress;

    private final DatePicker eventFrom = new DatePicker("Начиная с");
    private final DatePicker eventTo = new DatePicker("По");
    private final ComboBox<String> eventType = new ComboBox<>("Тип события");
    private final TextField userIdFilter = new TextField("Пользователь");
    private final TextField operationFilter = new TextField("Операция");
    private final TextField entityFilter = new TextField("Сущность");
    private final TextField minDurationFilter = new TextField("Мин. длительность, мс");
    private final Checkbox n1Only = new Checkbox("Только N+1");

    private final TextField changeEntityFilter = new TextField("Сущность");
    private final TextField changeEntityIdFilter = new TextField("ID записи");
    private final TextField changeUserFilter = new TextField("Пользователь");
    private final DatePicker changeFrom = new DatePicker("Начиная с");
    private final DatePicker changeTo = new DatePicker("По");

    private final ComboBox<String> aggScope = new ComboBox<>("Область");
    private final Button traceButton = new Button();
    private final ComboBox<Integer> traceMinutes = new ComboBox<>();
    private final Span healthLabel = new Span();

    public AdminView(@Autowired JournalQueryService journal,
                     @Autowired FieldAuditQueryService fieldAudit,
                     @Autowired Optional<TraceService> traceServiceOpt,
                     @Autowired AccessGrantAdminService accessGrantAdminService,
                     @Autowired SettingsAdminTab settingsTab,
                     @Autowired NumberingAdminTab numberingTab) {
        this.journal = journal;
        this.fieldAudit = fieldAudit;
        this.traceService = traceServiceOpt.orElse(null);
        this.accessGrantAdminService = accessGrantAdminService;
        this.settingsTab = settingsTab;
        this.numberingTab = numberingTab;
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
        Tab historyItem = new Tab(new Span("История изменений"), new Icon(VaadinIcon.CLOCK));
        Tab accessItem = new Tab(new Span("Доступ (RLS)"), new Icon(VaadinIcon.KEY));
        Tab settingsItem = new Tab(new Span("Настройки"), new Icon(VaadinIcon.COGS));
        Tab numberingItem = new Tab(new Span("Нумерация"), new Icon(VaadinIcon.HASH));
        Tabs tabs = new Tabs(journalItem, aggregatesItem, traceItem, historyItem, accessItem,
                settingsItem, numberingItem);
        add(tabs);

        buildJournalTab();
        buildAggregatesTab();
        buildTraceTab();
        buildHistoryTab();
        buildAccessTab();

        add(journalTab, aggregatesTab, traceTab, historyTab, accessTab, settingsTab, numberingTab);
        show(journalTab);

        tabs.addSelectedChangeListener(e -> {
            if (e.getSelectedTab() == aggregatesItem) {
                show(aggregatesTab);
            } else if (e.getSelectedTab() == traceItem) {
                show(traceTab);
                refreshHealth();
            } else if (e.getSelectedTab() == historyItem) {
                show(historyTab);
            } else if (e.getSelectedTab() == accessItem) {
                show(accessTab);
            } else if (e.getSelectedTab() == settingsItem) {
                show(settingsTab);
                settingsTab.refresh();
            } else if (e.getSelectedTab() == numberingItem) {
                show(numberingTab);
                numberingTab.refresh();
            } else {
                show(journalTab);
            }
        });
    }

    private void show(VerticalLayout active) {
        journalTab.setVisible(false);
        aggregatesTab.setVisible(false);
        traceTab.setVisible(false);
        historyTab.setVisible(false);
        accessTab.setVisible(false);
        settingsTab.setVisible(false);
        numberingTab.setVisible(false);
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
                + "\nзаписано изменений полей: " + health.writtenFieldChanges()
                + "\nпотеряно (drop): " + health.dropped()
                + "\nупавших батчей: " + health.failedBatches()
                + (health.lastError() != null ? "\nпоследняя ошибка: " + health.lastError() : ""));
    }

    // ------------------------------------------------ история изменений (этап 10)

    private void buildHistoryTab() {
        HorizontalLayout filters = new HorizontalLayout(changeEntityFilter, changeEntityIdFilter,
                changeUserFilter, changeFrom, changeTo);
        filters.setAlignItems(Alignment.END);
        filters.setSpacing(true);
        filters.setWrap(true);

        Button apply = new Button("Применить", new Icon(VaadinIcon.REFRESH),
                e -> refreshChanges());
        Button details = new Button("Подробности", new Icon(VaadinIcon.LIST),
                e -> openSelectedChange());
        details.setEnabled(false);

        changeGrid.setSelectionMode(Grid.SelectionMode.SINGLE);
        changeGrid.addSelectionListener(e ->
                details.setEnabled(!e.getFirstSelectedItem().isEmpty()));
        changeGrid.addItemDoubleClickListener(e -> openChangeDialog(e.getItem().id()));

        changeGrid.addColumn(r -> r.changedAt() == null ? "" : TIME.format(
                r.changedAt().atZone(ZoneId.systemDefault()))).setHeader("Время");
        changeGrid.addColumn(ChangeRow::changeType).setHeader("Тип");
        changeGrid.addColumn(r -> r.entity() + " #" + r.entityId()).setHeader("Сущность")
                .setAutoWidth(true);
        changeGrid.addColumn(ChangeRow::userId).setHeader("Пользователь");
        changeGrid.addColumn(ChangeRow::fieldCount).setHeader("Полей");
        changeGrid.addColumn(r -> trace(r.traceId())).setHeader("Trace ID");

        HorizontalLayout actions = new HorizontalLayout(apply, details);

        historyTab.setSpacing(true);
        historyTab.setSizeFull();
        historyTab.add(filters, actions, changeGrid);
        historyTab.setFlexGrow(1, changeGrid);

        refreshChanges();
    }

    private void refreshChanges() {
        ZoneId zone = ZoneId.systemDefault();
        ChangeFilter filter = new ChangeFilter(
                blankToNull(changeEntityFilter.getValue()),
                blankToNull(changeEntityIdFilter.getValue()),
                blankToNull(changeUserFilter.getValue()),
                dateToInstant(changeFrom.getValue(), zone, true),
                dateToInstant(changeTo.getValue(), zone, false),
                500);
        try {
            changeGrid.setItems(fieldAudit.queryChanges(filter));
        } catch (RuntimeException e) {
            // Vaadin UI-поток может не иметь SecurityContext: показываем ошибку,
            // а не молча пустой грид (queryChanges требует ROLE_ADMIN).
            changeGrid.setItems(java.util.List.of());
            com.vaadin.flow.component.notification.Notification.show(
                    "Ошибка загрузки истории: " + e.getMessage(), 5000,
                    com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
        }
    }

    private void openSelectedChange() {
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

    // ------------------------------------------------------ доступ (RLS)

    /** Строка матрицы: запись измерения + текущее (редактируемое) состояние трёх флагов. */
    private static final class DimensionValueAccessRow {
        final AccessGrantAdminService.ValueRow value;
        boolean read;
        boolean update;
        boolean delete;

        DimensionValueAccessRow(AccessGrantAdminService.ValueRow value, GrantFlags flags) {
            this.value = value;
            this.read = flags.read();
            this.update = flags.update();
            this.delete = flags.delete();
        }
    }

    private void buildAccessTab() {
        accessDimension.setItems(accessGrantAdminService.availableDimensions());
        accessDimension.setAllowCustomValue(false);
        if (!accessGrantAdminService.availableDimensions().isEmpty()) {
            accessDimension.setValue(accessGrantAdminService.availableDimensions().get(0));
        }

        accessSubjectType.setItems(AccessGrant.SubjectType.values());
        accessSubjectType.setItemLabelGenerator(t ->
                t == AccessGrant.SubjectType.USER ? "Пользователь" : "Роль");
        accessSubjectType.setValue(AccessGrant.SubjectType.USER);
        accessSubjectType.setAllowCustomValue(false);

        accessSubjectKey.setAllowCustomValue(false);
        refreshSubjectKeyItems();

        accessDimension.addValueChangeListener(e -> loadAccessMatrix());
        accessSubjectType.addValueChangeListener(e -> {
            accessSubjectKey.clear();
            refreshSubjectKeyItems();
            loadAccessMatrix();
        });
        accessSubjectKey.addValueChangeListener(e -> loadAccessMatrix());

        Button saveButton = saveAccessButton;
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        accessGrid.addColumn(r -> r.value.code()).setHeader("Код").setAutoWidth(true);
        accessGrid.addColumn(r -> r.value.name()).setHeader("Наименование").setFlexGrow(1);
        accessGrid.addComponentColumn(this::buildReadCheckbox).setHeader("Чтение").setWidth("100px").setFlexGrow(0);
        accessGrid.addComponentColumn(this::buildUpdateCheckbox).setHeader("Изменение").setWidth("110px").setFlexGrow(0);
        accessGrid.addComponentColumn(this::buildDeleteCheckbox).setHeader("Удаление").setWidth("100px").setFlexGrow(0);

        // Для CHECK_ONLY-измерений ("доступ к виду документа целиком", без построчного
        // списка записей) — три обычных чекбокса вместо грида, см. loadAccessMatrix/
        // saveAccessMatrix и AccessGrantAdminService.kindOf.
        singleGrantUpdate.setEnabled(false);
        singleGrantDelete.setEnabled(false);
        singleGrantRead.addValueChangeListener(e -> {
            singleGrantUpdate.setEnabled(e.getValue());
            singleGrantDelete.setEnabled(e.getValue());
            if (!e.getValue()) {
                singleGrantUpdate.setValue(false);
                singleGrantDelete.setValue(false);
            }
        });
        HorizontalLayout singleGrantRow = new HorizontalLayout(
                new Span("Доступ:"), singleGrantRead,
                new Span("Изменение:"), singleGrantUpdate,
                new Span("Удаление:"), singleGrantDelete);
        singleGrantRow.setAlignItems(Alignment.CENTER);
        singleGrantRow.setSpacing(true);

        Span hint = new Span("Запись без отмеченного \"Чтение\" для выбранного пользователя/роли — " +
                "недоступна вообще (запись гранта не создаётся). \"Изменение\"/\"Удаление\" " +
                "без \"Чтение\" не имеют смысла — отключены, пока не отмечено \"Чтение\".");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)");

        HorizontalLayout subjectRow = new HorizontalLayout(accessDimension, accessSubjectType, accessSubjectKey,
                saveButton, effectiveRightsButton);
        subjectRow.setAlignItems(Alignment.END);
        subjectRow.setSpacing(true);

        accessTab.setSpacing(true);
        accessTab.setSizeFull();
        accessTab.add(subjectRow, hint, singleGrantRow, accessGrid);
        accessTab.setFlexGrow(1, accessGrid);
        this.singleGrantRowLayout = singleGrantRow;

        loadAccessMatrix();
    }

    private void refreshSubjectKeyItems() {
        accessSubjectKey.setItems(accessSubjectType.getValue() == AccessGrant.SubjectType.USER
                ? accessGrantAdminService.allUsernames()
                : accessGrantAdminService.allRoleNames());
    }

    private Checkbox buildReadCheckbox(DimensionValueAccessRow row) {
        Checkbox checkbox = new Checkbox(row.read);
        checkbox.addValueChangeListener(e -> {
            row.read = e.getValue();
            if (!row.read) {
                row.update = false;
                row.delete = false;
            }
            accessGrid.getDataProvider().refreshItem(row);
        });
        return checkbox;
    }

    private Checkbox buildUpdateCheckbox(DimensionValueAccessRow row) {
        Checkbox checkbox = new Checkbox(row.update);
        checkbox.setEnabled(row.read);
        checkbox.addValueChangeListener(e -> row.update = e.getValue());
        return checkbox;
    }

    private Checkbox buildDeleteCheckbox(DimensionValueAccessRow row) {
        Checkbox checkbox = new Checkbox(row.delete);
        checkbox.setEnabled(row.read);
        checkbox.addValueChangeListener(e -> row.delete = e.getValue());
        return checkbox;
    }

    private void loadAccessMatrix() {
        String dimension = accessDimension.getValue();
        String subjectKey = accessSubjectKey.getValue();

        boolean checkOnly = dimension != null
                && accessGrantAdminService.kindOf(dimension) == org.ipro.rls.RlsDimensionKind.CHECK_ONLY;
        accessGrid.setVisible(!checkOnly);
        singleGrantRowLayout.setVisible(checkOnly);

        if (dimension == null || subjectKey == null) {
            accessRows.clear();
            accessGrid.setItems(accessRows);
            return;
        }

        if (checkOnly) {
            GrantFlags flags = accessGrantAdminService.currentSingleGrant(dimension, accessSubjectType.getValue(), subjectKey);
            singleGrantRead.setValue(flags.read());
            singleGrantUpdate.setValue(flags.update());
            singleGrantUpdate.setEnabled(flags.read());
            singleGrantDelete.setValue(flags.delete());
            singleGrantDelete.setEnabled(flags.read());
            return;
        }

        accessRows.clear();
        Map<Long, GrantFlags> current = accessGrantAdminService.currentGrantsByDimensionValue(
                dimension, accessSubjectType.getValue(), subjectKey);
        for (AccessGrantAdminService.ValueRow value : accessGrantAdminService.allValues(dimension)) {
            accessRows.add(new DimensionValueAccessRow(value, current.getOrDefault(value.id(), GrantFlags.NONE)));
        }
        accessGrid.setItems(accessRows);
    }

    private void saveAccessMatrix() {
        String dimension = accessDimension.getValue();
        String subjectKey = accessSubjectKey.getValue();
        if (dimension == null || subjectKey == null) {
            Notification.show("Выберите измерение и пользователя или роль", 3000, Notification.Position.MIDDLE);
            return;
        }

        // Межлок: повторный клик по «Сохранить», пока первый запрос в полёте, игнорируется.
        if (accessSaveInProgress) {
            return;
        }
        accessSaveInProgress = true;
        saveAccessButton.setEnabled(false);
        try {
            if (accessGrantAdminService.kindOf(dimension) == org.ipro.rls.RlsDimensionKind.CHECK_ONLY) {
                GrantFlags flags = new GrantFlags(
                        singleGrantRead.getValue(), singleGrantUpdate.getValue(), singleGrantDelete.getValue());
                accessGrantAdminService.saveSingleGrant(dimension, accessSubjectType.getValue(), subjectKey, flags);
            } else {
                Map<Long, GrantFlags> desired = new java.util.HashMap<>();
                for (DimensionValueAccessRow row : accessRows) {
                    desired.put(row.value.id(), new GrantFlags(row.read, row.update, row.delete));
                }
                accessGrantAdminService.saveGrants(dimension, accessSubjectType.getValue(), subjectKey, desired);
            }

            Notification.show("Права сохранены для " + subjectKey, 2500, Notification.Position.BOTTOM_START)
                    .addThemeVariants(com.vaadin.flow.component.notification.NotificationVariant.LUMO_SUCCESS);
        } finally {
            accessSaveInProgress = false;
            saveAccessButton.setEnabled(true);
        }
    }

    /** Строка диалога «Эффективные права»: измерение + свёрнутое состояние и источник. */
    private static final class EffectiveRightsRow {
        final String dimension;
        final String values;
        final String read;
        final String update;
        final String delete;
        final String sources;

        EffectiveRightsRow(String dimension, String values, String read, String update,
                           String delete, String sources) {
            this.dimension = dimension;
            this.values = values;
            this.read = read;
            this.update = update;
            this.delete = delete;
            this.sources = sources;
        }
    }

    /**
     * Диалог «Эффективные права» (Фаза 7 RLS-плана): по выбранному субъекту — свёртка
     * прямых грантов и всех его ролей (с учётом wildcard "*") по каждому измерению.
     * Показывает ИТОГОВЫЙ доступ, которого на самом деле придерживается система, —
     * в отличие от матрицы редактирования, где видны только прямые строки субъекта.
     */
    private void openEffectiveRightsDialog() {
        String subjectKey = accessSubjectKey.getValue();
        if (subjectKey == null) {
            Notification.show("Выберите пользователя или роль", 3000, Notification.Position.MIDDLE);
            return;
        }
        AccessGrant.SubjectType subjectType = accessSubjectType.getValue();
        Map<String, org.ipro.rls.AccessService.EffectiveGrant> effective =
                accessGrantAdminService.collectEffective(subjectType, subjectKey);

        List<EffectiveRightsRow> rows = new ArrayList<>();
        for (Map.Entry<String, org.ipro.rls.AccessService.EffectiveGrant> entry : effective.entrySet()) {
            org.ipro.rls.AccessService.EffectiveGrant grant = entry.getValue();
            rows.add(new EffectiveRightsRow(
                    entry.getKey(),
                    formatEffectiveValues(entry.getKey(), grant),
                    yesNo(grant.canRead()),
                    yesNo(grant.canUpdate()),
                    yesNo(grant.canDelete()),
                    grant.sources().isEmpty() ? "—" : String.join(", ", grant.sources())));
        }

        Grid<EffectiveRightsRow> grid = new Grid<>(EffectiveRightsRow.class, false);
        grid.addColumn(r -> r.dimension).setHeader("Измерение").setAutoWidth(true);
        grid.addColumn(r -> r.values).setHeader("Записи").setFlexGrow(1);
        grid.addColumn(r -> r.read).setHeader("Чтение").setWidth("90px").setFlexGrow(0);
        grid.addColumn(r -> r.update).setHeader("Изменение").setWidth("90px").setFlexGrow(0);
        grid.addColumn(r -> r.delete).setHeader("Удаление").setWidth("90px").setFlexGrow(0);
        grid.addColumn(r -> r.sources).setHeader("Источник").setWidth("260px").setFlexGrow(0);
        grid.setItems(rows);

        Dialog dialog = new Dialog(grid);
        dialog.setHeaderTitle("Эффективные права: " + subjectKey
                + (subjectType == AccessGrant.SubjectType.USER ? " (пользователь)" : " (роль)"));
        dialog.setWidth("820px");
        dialog.setHeight("480px");
        dialog.open();
    }

    /** Колонка «Записи»: "все" — wildcard-грант; "—" — CHECK_ONLY или нет прав на чтение; иначе коды записей. */
    private String formatEffectiveValues(String dimension, org.ipro.rls.AccessService.EffectiveGrant grant) {
        if (accessGrantAdminService.kindOf(dimension) == org.ipro.rls.RlsDimensionKind.CHECK_ONLY
                || !grant.canRead()) {
            return "—";
        }
        if (grant.unlimited()) {
            return "все";
        }
        Map<Long, String> codesById = new java.util.HashMap<>();
        for (AccessGrantAdminService.ValueRow value : accessGrantAdminService.allValues(dimension)) {
            codesById.put(value.id(), value.code());
        }
        return grant.readableValueIds().stream()
                .map(id -> codesById.getOrDefault(id, String.valueOf(id)))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String yesNo(boolean value) {
        return value ? "Да" : "—";
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
