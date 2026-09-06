package org.ip.views.admin;

import java.util.List;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.data.provider.SortDirection;

import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ipro.telemetry.api.TraceService;
import org.ipro.telemetry.api.UserContext;
import org.ipro.telemetry.core.JournalQueryService;
import org.ipro.telemetry.core.JournalSearchService;
import org.ipro.telemetry.model.OperationLogEntity;
import org.springframework.data.jpa.domain.Specification;

/**
 * Вкладка «Трассировка»: master-detail браузер L2-трасс. Master — грид
 * событий TRACE (ленивый, с пресетами и фильтрами колонок через
 * FilterGrid), Detail — PayloadTreeView выбранной трассы (дерево фреймов
 * + SQL) без диалога. Вверху — включение/выключение окна трассировки и
 * состояние async-writer'а (self-observation).
 */
final class TracePanel extends VerticalLayout {

    private static final String WARN_TEXT =
            "Внимание: трассировка фиксирует каждый сервисный вызов и каждый SQL и влияет "
                    + "на производительность. Окно автоматически отключается по истечении минут.";

    private final JournalQueryService journal;
    private final JournalSearchService search;
    private final TraceService traceService;

    private final JpaFilterGrid<OperationLogEntity> master;
    private final ComboBox<Integer> minutes = new ComboBox<>("Окно (мин)");
    private final Button toggleButton = new Button();
    private final Span health = new Span();
    private final VerticalLayout detailContainer = new VerticalLayout();

    TracePanel(JournalQueryService journal, JournalSearchService search, TraceService traceService) {
        this.journal = journal;
        this.search = search;
        this.traceService = traceService;
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        Specification<OperationLogEntity> traceOnly = JournalSearchService.eventTypeEq("TRACE");
        master = new JpaFilterGrid<>(OperationLogEntity.class,
                (spec, pageable) -> search.search(
                        spec == null ? traceOnly : spec.and(traceOnly), pageable));
        master.setCompact(true);
        configureMaster();
        master.build();
        master.getGrid().setSelectionMode(Grid.SelectionMode.SINGLE);

        VerticalLayout masterLayout = new VerticalLayout(buildControls(), master);
        masterLayout.setPadding(false);
        masterLayout.setSpacing(false);
        masterLayout.setSizeFull();
        masterLayout.setFlexGrow(1, master);

        detailContainer.setPadding(false);
        detailContainer.setSizeFull();
        detailContainer.add(new Span("Выберите трассу в списке сверху — здесь появится её дерево."));

        SplitLayout split = new SplitLayout(masterLayout, detailContainer);
        split.setOrientation(SplitLayout.Orientation.VERTICAL);
        split.setSplitterPosition(45);
        split.setSizeFull();

        add(split);
        refreshHealth();
    }

    private void configureMaster() {
        master.addComponentColumn("time", "Время", row -> {
            Span span = new Span(TelemetryUi.formatTime(row.getStartedAt()));
            span.setTitle(TelemetryUi.fullTrace(row.getTraceId()));
            return span;
        });
        master.addColumn("userId", "Пользователь", OperationLogEntity::getUserId);
        master.addColumn("operation", "Операция", OperationLogEntity::getOperation).setAutoWidth(true);
        master.addColumn("durationMs", "Длит., мс", row -> row.getDurationMs() == null
                ? "" : String.valueOf(Math.round(row.getDurationMs())));
        master.addColumn("sqlCount", "SQL", row -> row.getSqlCount() == null
                ? "" : String.valueOf(row.getSqlCount()));
        master.addColumn("sqlTotalMs", "SQL, мс", row -> row.getSqlTotalMs() == null
                ? "" : String.valueOf(Math.round(row.getSqlTotalMs())));
        master.addColumn("traceId", "Trace ID", row -> TelemetryUi.shortTrace(row.getTraceId()))
                .setTooltipGenerator(row -> row.getTraceId() == null ? "" : row.getTraceId());

        Grid.Column<OperationLogEntity> idColumn = master.addColumn("id", "ID", row -> String.valueOf(row.getId()));
        master.getGrid().sort(List.of(new GridSortOrder<>(idColumn, SortDirection.DESCENDING)));

        master.getGrid().addSelectionListener(e -> e.getFirstSelectedItem().ifPresent(this::showDetail));
    }

    private HorizontalLayout buildControls() {
        minutes.setItems(2, 5, 10);
        minutes.setValue(2);
        minutes.setWidth("120px");

        toggleButton.addClickListener(e -> toggleTrace());
        refreshToggleButton();

        Button refresh = new Button("Обновить", new Icon(VaadinIcon.REFRESH), e -> {
            master.refreshAll();
            refreshHealth();
        });

        health.getStyle().set("color", "var(--lumo-secondary-text-color)");
        health.getStyle().set("white-space", "pre-wrap");
        health.getStyle().set("font-size", "var(--lumo-font-size-s)");

        Span warn = new Span(WARN_TEXT);
        warn.getStyle().set("color", "var(--lumo-error-text-color)");
        warn.getStyle().set("font-size", "var(--lumo-font-size-s)");

        HorizontalLayout controls = new HorizontalLayout(toggleButton, minutes, refresh, warn);
        controls.setAlignItems(FlexComponent.Alignment.CENTER);
        controls.setSpacing(true);
        controls.setWrap(true);
        return controls;
    }

    private void showDetail(OperationLogEntity row) {
        detailContainer.removeAll();
        if (row.getPayload() == null || row.getPayload().isBlank()) {
            detailContainer.add(new Span("payload отсутствует"));
            return;
        }
        detailContainer.add(PayloadTreeView.build(row.getPayload()));
    }

    private void toggleTrace() {
        if (traceService == null) {
            Notification.show("Телеметрия выключена (ipro.telemetry.enabled=false)");
            return;
        }
        String user = UserContext.defaultInstance().currentUsername();
        if (traceService.isTraceActive(user)) {
            traceService.stopTrace(user);
            Notification.show("Трассировка отключена для " + user, 3000, Notification.Position.MIDDLE);
        } else {
            int value = minutes.getValue() == null ? 2 : minutes.getValue();
            traceService.startTrace(user, value);
            Notification.show("Трассировка включена на " + value + " мин для " + user,
                    3000, Notification.Position.MIDDLE);
        }
        refreshToggleButton();
    }

    private void refreshToggleButton() {
        if (traceService == null) {
            toggleButton.setText("Телеметрия выключена");
            toggleButton.setEnabled(false);
            return;
        }
        String user = UserContext.defaultInstance().currentUsername();
        if (traceService.isTraceActive(user)) {
            toggleButton.setText("Отключить трассировку (" + user + ")");
            toggleButton.setIcon(new Icon(VaadinIcon.CLOSE_CIRCLE_O));
            toggleButton.addThemeVariants(ButtonVariant.LUMO_ERROR);
        } else {
            toggleButton.setText("Включить трассировку");
            toggleButton.setIcon(new Icon(VaadinIcon.BUG));
            toggleButton.removeThemeVariants(ButtonVariant.LUMO_ERROR);
        }
    }

    private void refreshHealth() {
        try {
            JournalQueryService.SinkHealth h = journal.sinkHealth();
            health.setText("writer: " + (h.active() ? "активен" : "выключен/noop")
                    + " · очередь: " + h.queueSize()
                    + " · записано событий: " + h.writtenEvents()
                    + " · потеряно: " + h.dropped()
                    + " · упавших батчей: " + h.failedBatches()
                    + (h.lastError() != null ? " · ошибка: " + h.lastError() : ""));
        } catch (RuntimeException e) {
            health.setText("health недоступен: " + e.getMessage());
        }
    }

    /** Вызывается при каждом показе вкладки (см. DiagnosticsView). */
    void onShow() {
        refreshToggleButton();
        refreshHealth();
    }
}
