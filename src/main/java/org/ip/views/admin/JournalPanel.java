package org.ip.views.admin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.function.ValueProvider;

import org.ipro.filtergrid.ComboBoxFilter;
import org.ipro.filtergrid.DateRangeFilter;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ipro.telemetry.core.JournalQueryService;
import org.ipro.telemetry.core.JournalSearchService;
import org.ipro.telemetry.model.OperationLogEntity;
import org.springframework.data.jpa.domain.Specification;

/**
 * Вкладка «Журнал» диагностики: FilterGrid (компактный режим, ленивая
 * пагинация LIMIT/OFFSET, фильтры в шапке колонок), временные пресеты,
 * цветовая разметка уровней, клик по Trace ID (вся трасса в этом же гриде),
 * двойной клик — диалог дерева операции.
 */
final class JournalPanel extends VerticalLayout {

    private static final List<String> EVENT_TYPES =
            List.of("PERF_METHOD", "PERF_SQL", "ACTION", "SECURITY", "ERROR", "APP", "TRACE");

    private final JournalQueryService journal;
    private final JournalSearchService search;

    private final JpaFilterGrid<OperationLogEntity> grid;
    private final DateRangeFilter<OperationLogEntity> dateRange = new DateRangeFilter<>();
    private final TextFilter<OperationLogEntity> traceIdFilter = new TextFilter<>("traceId");
    private final ComboBoxFilter<OperationLogEntity, String> typeFilter = new ComboBoxFilter<>();
    private final ComboBoxFilter<OperationLogEntity, String> levelFilter = new ComboBoxFilter<>();
    private final IntegerField minDuration = new IntegerField("Мин. длительность, мс");
    private final Checkbox n1Only = new Checkbox("Только N+1");
    private final Button traceReset = new Button("Сбросить трассу", new Icon(VaadinIcon.CLOSE_SMALL));
    private final Span info = new Span();
    private TelemetryPresets presets;

    JournalPanel(JournalQueryService journal, JournalSearchService search) {
        this.journal = journal;
        this.search = search;
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        grid = new JpaFilterGrid<>(OperationLogEntity.class,
                (spec, pageable) -> search.search(compose(spec), pageable));
        grid.setCompact(true);
        configureColumns();
        grid.build();
        // Фон строк целиком: ERROR/WARN (см. telemetry-grid.css, ::part).
        grid.getGrid().setPartNameGenerator(row -> switch (row.getLevel() == null ? "" : row.getLevel().toUpperCase()) {
            case "ERROR" -> "tl-row-error";
            case "WARN" -> "tl-row-warn";
            default -> null;
        });
        grid.getGrid().addItemDoubleClickListener(e ->
                PayloadDialog.open(journal, e.getItem().getId()));

        presets = new TelemetryPresets(dateRange, p -> refresh());

        add(buildToolbar(), info, grid);
        setFlexGrow(1, grid);

        presets.apply(TelemetryPresets.Preset.DAYS7);
    }

    // ------------------------------------------------------- конфигурация

    private void configureColumns() {
        grid.addComponentColumn("time", "Время",
                (ValueProvider<OperationLogEntity, Span>) row -> {
                    Span span = new Span(TelemetryUi.formatTime(row.getStartedAt()));
                    span.setTitle(TelemetryUi.fullTrace(row.getTraceId()));
                    return span;
                });

        grid.addColumn("eventType", "Тип", OperationLogEntity::getEventType);
        grid.addFilter("eventType", "Тип", typeFilter);

        grid.addComponentColumn("level", "Уровень", row -> {
            Span span = new Span(row.getLevel() == null ? "" : row.getLevel());
            span.addClassName(TelemetryUi.levelClass(row.getLevel()));
            return span;
        });
        grid.addFilter("level", "Уровень", levelFilter);

        grid.addColumn("userId", "Пользователь", OperationLogEntity::getUserId);
        grid.addColumn("operation", "Операция", OperationLogEntity::getOperation).setAutoWidth(true);
        grid.addColumn("entity", "Сущность", row -> row.getEntity() == null
                ? "" : row.getEntity() + (row.getEntityId() != null ? " #" + row.getEntityId() : ""))
                .setAutoWidth(true);
        grid.addColumn("durationMs", "Длит., мс", row -> row.getDurationMs() == null
                ? "" : String.valueOf(Math.round(row.getDurationMs())));
        grid.addColumn("sqlCount", "SQL", row -> row.getSqlCount() == null
                ? "" : String.valueOf(row.getSqlCount()));
        grid.addColumn("sqlTotalMs", "SQL, мс", row -> row.getSqlTotalMs() == null
                ? "" : String.valueOf(Math.round(row.getSqlTotalMs())));
        grid.addColumn("n1", "N+1", row -> row.isN1() ? "N+1" : "");

        grid.addComponentColumn("traceId", "Trace ID", row -> {
            String traceId = row.getTraceId();
            Span link = new Span(TelemetryUi.shortTrace(traceId));
            if (traceId != null && !traceId.isBlank()) {
                link.addClassName("tl-trace-cell");
                link.setTitle("Показать все события трассы " + traceId);
                link.getElement().addEventListener("click",
                        e -> showTrace(traceId));
            }
            return link;
        });
        // Тот же фильтр доступен и вручную — в шапке колонки Trace ID.
        grid.addFilter("traceId", "Trace ID", traceIdFilter);

        grid.addColumn("errorMessage", "Ошибка", row -> row.getErrorMessage() == null
                ? "" : firstLine(row.getErrorMessage()))
                .setAutoWidth(true)
                .setTooltipGenerator(row -> row.getErrorMessage() == null ? "" : row.getErrorMessage());

        Grid.Column<OperationLogEntity> idColumn = grid.addColumn("id", "ID", row ->
                String.valueOf(row.getId()));
        grid.getGrid().sort(List.of(new GridSortOrder<>(idColumn, SortDirection.DESCENDING)));

        // Фильтры-компоненты: списки значений и плейсхолдеры.
        typeFilter.setItems(EVENT_TYPES);
        ((ComboBox<String>) typeFilter.getComponent()).setPlaceholder("Любой");
        levelFilter.setItems("INFO", "WARN", "ERROR");
        ((ComboBox<String>) levelFilter.getComponent()).setPlaceholder("Любой");
        traceIdFilter.getTextField().setPlaceholder("traceId…");
        dateRange.getDateFrom().setPlaceholder("с…");
        dateRange.getDateTo().setPlaceholder("по…");
    }

    private static String firstLine(String text) {
        int idx = text.indexOf('\n');
        return idx < 0 ? text : text.substring(0, idx);
    }

    private HorizontalLayout buildToolbar() {
        minDuration.setWidth("150px");
        minDuration.setMin(0);
        minDuration.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.LAZY);
        minDuration.addValueChangeListener(e -> refresh());

        // Ручная правка дат тоже перечитывает данные (пресеты — через свой слушатель).
        dateRange.getDateFrom().addValueChangeListener(e -> refresh());
        dateRange.getDateTo().addValueChangeListener(e -> refresh());

        n1Only.addValueChangeListener(e -> refresh());

        traceReset.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
        traceReset.setVisible(false);
        traceReset.addClickListener(e -> showTrace(null));

        Button refresh = new Button("Обновить", new Icon(VaadinIcon.REFRESH), e -> refresh());

        HorizontalLayout bar = new HorizontalLayout(
                presets, dateRange.getComponent(), minDuration, n1Only, traceReset, refresh);
        bar.setAlignItems(FlexComponent.Alignment.END);
        bar.setSpacing(true);
        bar.setWrap(true);
        return bar;
    }

    // ------------------------------------------------------------- данные

    /** Нижняя граница периода из тулбара (дата «с», включительно). */
    private java.time.Instant from() {
        LocalDate date = dateRange.getDateFrom().getValue();
        return date == null ? null : date.atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    /** Верхняя граница периода из тулбара (дата «по», не включительно). */
    private java.time.Instant to() {
        LocalDate date = dateRange.getDateTo().getValue();
        return date == null ? null : date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    /**
     * Итоговая спецификация: фильтры колонок грида + доп. условия тулбара
     * (мин. длительность, «только N+1»).
     */
    private Specification<OperationLogEntity> compose(Specification<OperationLogEntity> gridSpec) {
        Long minMs = minDuration.getValue() == null ? null : minDuration.getValue().longValue();
        Specification<OperationLogEntity> result = gridSpec == null
                ? JournalSearchService.durationGte(minMs)
                : gridSpec.and(JournalSearchService.durationGte(minMs));
        if (n1Only.getValue()) {
            result = result.and(JournalSearchService.n1True());
        }
        // Период: пресеты/поля тулбара (не фильтр колонки — у сущности нет пути "time").
        result = result
                .and(JournalSearchService.startedAtGte(from()))
                .and(JournalSearchService.startedAtLte(to()));
        return result;
    }

    /** Показать все события одной трассы; повторный вызов с null — сброс. */
    void showTrace(String traceId) {
        boolean active = traceId != null && !traceId.isBlank();
        if (active) {
            traceIdFilter.getTextField().setValue(traceId);
        } else {
            traceIdFilter.getTextField().clear();
        }
        traceReset.setVisible(active);
        info.setText(active
                ? "Показаны события трассы " + traceId + " (фильтр Trace ID в шапке грида)."
                : "");
        info.setVisible(active);
        refresh();
    }

    void refresh() {
        grid.refreshAll();
    }

    Grid<OperationLogEntity> grid() {
        return grid.getGrid();
    }
}
