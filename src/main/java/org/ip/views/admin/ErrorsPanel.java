package org.ip.views.admin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.function.ValueProvider;

import org.ipro.telemetry.core.JournalQueryService;
import org.ipro.telemetry.core.JournalSearchService;

/**
 * Вкладка «Ошибки»: группировка одинаковых ошибок (операция + первая строка
 * текста) — «1 инцидент × N раз, первое/последнее появление» вместо плоского
 * списка. «Показать» открывает drill-down: плоский список событий группы
 * (двойной клик или кнопка — дерево операции).
 */
final class ErrorsPanel extends VerticalLayout {

    private final JournalQueryService journal;
    private final JournalSearchService search;

    private final Grid<JournalSearchService.ErrorGroupRow> groupGrid = new Grid<>();
    private final TextField userFilter = new TextField("Пользователь");
    private final TextField operationFilter = new TextField("Операция");
    private final TelemetryPresets presets;

    ErrorsPanel(JournalQueryService journal, JournalSearchService search) {
        this.journal = journal;
        this.search = search;
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        buildGroupGrid();

        userFilter.setPlaceholder("часть имени…");
        userFilter.setClearButtonVisible(true);
        userFilter.setValueChangeMode(ValueChangeMode.LAZY);
        userFilter.addValueChangeListener(e -> refresh());
        operationFilter.setPlaceholder("часть имени операции…");
        operationFilter.setClearButtonVisible(true);
        operationFilter.setValueChangeMode(ValueChangeMode.LAZY);
        operationFilter.addValueChangeListener(e -> refresh());

        // Пресеты периода управляют нижней границей выборки групп. Конструктор
        // пресетов сам выставляет значение по умолчанию (Дней 7) и дергает refresh.
        DateRangeBridge bridge = new DateRangeBridge();
        this.presets = new TelemetryPresets(bridge, p -> refresh());

        Button refresh = new Button("Обновить", new Icon(VaadinIcon.REFRESH), e -> refresh());

        HorizontalLayout bar = new HorizontalLayout(presets, userFilter, operationFilter, refresh);
        bar.setAlignItems(FlexComponent.Alignment.END);
        bar.setSpacing(true);
        bar.setWrap(true);

        add(bar, groupGrid);
        setFlexGrow(1, groupGrid);
    }

    /** Мостик: пресеты не требуют полноценного DateRangeFilter — пишут в from. */
    private static final class DateRangeBridge extends org.ipro.filtergrid.DateRangeFilter<Object> {
        @Override
        public HorizontalLayout getComponent() {
            return new HorizontalLayout();
        }
    }

    private void buildGroupGrid() {
        groupGrid.setSelectionMode(Grid.SelectionMode.SINGLE);
        groupGrid.addColumn((ValueProvider<JournalSearchService.ErrorGroupRow, Object>) r -> r.count())
                .setHeader("Кол-во").setWidth("90px").setFlexGrow(0).setSortable(true);
        groupGrid.addColumn(JournalSearchService.ErrorGroupRow::distinctUsers)
                .setHeader("Польз.").setWidth("80px").setFlexGrow(0).setSortable(true);
        groupGrid.addColumn(r -> TelemetryUi.formatTime(r.firstSeen()))
                .setHeader("Первое").setAutoWidth(true);
        groupGrid.addColumn(r -> TelemetryUi.formatTime(r.lastSeen()))
                .setHeader("Последнее").setAutoWidth(true);
        groupGrid.addColumn(r -> r.operation() == null ? "—" : r.operation())
                .setHeader("Операция").setAutoWidth(true);
        groupGrid.addColumn(r -> r.errorFingerprint() == null ? "" : r.errorFingerprint())
                .setHeader("Ошибка (первая строка)").setFlexGrow(1);

        groupGrid.addComponentColumn(row -> {
            Button show = new Button("Показать", new Icon(VaadinIcon.SEARCH));
            show.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            show.addClickListener(e -> openDrillDown(row));
            return show;
        }).setHeader("").setWidth("110px").setFlexGrow(0);

        groupGrid.setSizeFull();
    }

    /** Drill-down: плоский список событий выбранной группы ошибок. */
    private void openDrillDown(JournalSearchService.ErrorGroupRow group) {
        List<JournalQueryService.EventRow> rows = journal.queryEvents(
                new JournalQueryService.EventFilter(
                        "ERROR", "ERROR", null, group.operation(), null,
                        null, null, null, false, group.errorFingerprint(), null, 1000));

        Grid<JournalQueryService.EventRow> grid = new Grid<>(JournalQueryService.EventRow.class, false);
        grid.addColumn(r -> TelemetryUi.formatTime(r.startedAt())).setHeader("Время").setAutoWidth(true);
        grid.addColumn(JournalQueryService.EventRow::userId).setHeader("Пользователь");
        grid.addColumn(JournalQueryService.EventRow::operation).setHeader("Операция").setAutoWidth(true);
        grid.addColumn(r -> TelemetryUi.shortTrace(r.traceId())).setHeader("Trace ID");
        grid.addColumn(JournalQueryService.EventRow::errorMessage).setHeader("Ошибка").setFlexGrow(1);
        grid.addComponentColumn(row -> PayloadDialog.openButton(journal, row.id()))
                .setHeader("").setWidth("60px").setFlexGrow(0);
        grid.addItemDoubleClickListener(e -> PayloadDialog.open(journal, e.getItem().id()));
        grid.setItems(rows);
        grid.setHeightFull();

        Dialog dialog = new Dialog(grid);
        dialog.setHeaderTitle("Ошибка ×" + group.count() + ": "
                + (group.operation() == null ? "—" : group.operation())
                + " — " + group.errorFingerprint());
        dialog.setWidth("1100px");
        dialog.setHeight("640px");
        Button close = new Button("Закрыть", e -> dialog.close());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        dialog.getFooter().add(close);
        dialog.open();
    }

    private void refresh() {
        JournalSearchService.ErrorGroupFilter filter = new JournalSearchService.ErrorGroupFilter(
                blankToNull(userFilter.getValue()),
                blankToNull(operationFilter.getValue()),
                presetFrom(), null);
        try {
            List<JournalSearchService.ErrorGroupRow> rows = search.errorGroups(filter);
            groupGrid.setItems(new ListDataProvider<>(rows));
        } catch (RuntimeException e) {
            groupGrid.setItems(new ListDataProvider<>(List.of()));
            com.vaadin.flow.component.notification.Notification.show(
                    "Ошибка загрузки групп: " + e.getMessage(), 5000,
                    com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
        }
    }

    /** Нижняя граница периода из активного пресета.
     *  presets == null — значение по умолчанию (последние 7 дней):
     *  конструктор пресетов дергает слушателя до присвоения поля. */
    private java.time.Instant presetFrom() {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (presets == null) {
            return today.minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant();
        }
        return switch (presets.current()) {
            case TODAY -> today.atStartOfDay(ZoneId.systemDefault()).toInstant();
            case YESTERDAY -> today.minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
            case DAYS7 -> today.minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant();
            case DAYS30 -> today.minusDays(30).atStartOfDay(ZoneId.systemDefault()).toInstant();
            case ALL -> null;
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
