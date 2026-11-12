package org.ip.views.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.ExplorerSnapshot;

public class EntityExplorerDiagnosticsView extends VerticalLayout {
    private final Span status = new Span();
    private final Grid<EntitySummary.DiagnosticRow> rows = new Grid<>();
    private final Button refresh = new Button("Обновить каталог");
    private long displayedRevision = -1;

    public EntityExplorerDiagnosticsView() {
        setSizeFull();
        status.getElement().setAttribute("role", "status");
        rows.setAriaLabel("Общие диагностики каталога сущностей");
        rows.addColumn(row -> EntitySummaryPanel.severityLabel(row.severity()))
            .setHeader("Уровень").setAutoWidth(true).setFlexGrow(0);
        rows.addColumn(EntitySummary.DiagnosticRow::code).setHeader("Код").setAutoWidth(true);
        rows.addColumn(EntitySummaryPanel::diagnosticAddress).setHeader("Адрес");
        rows.addColumn(row -> row.value().value()).setHeader("Сообщение").setFlexGrow(2);
        rows.getElement().getThemeList().add("wrap-cell-content");
        add(new H2("Общие диагностики"), refresh, status, rows);
        setFlexGrow(1, rows);
    }

    void initialize(Runnable onRefresh) {
        refresh.addClickListener(event -> onRefresh.run());
    }

    void show(ExplorerSnapshot snapshot, long revision) {
        if (displayedRevision == revision) return;
        displayedRevision = revision;
        var diagnostics = snapshot.unassignedDiagnostics();
        rows.setItems(diagnostics);
        rows.setVisible(!diagnostics.isEmpty());
        refresh.setEnabled(true);
        String summary = snapshot.diagnosticsAvailable()
            ? (diagnostics.isEmpty() ? "Общих диагностик нет" : "Сообщений: " + diagnostics.size())
            : "Результаты проверки метаданных недоступны"
                + (diagnostics.isEmpty() ? "" : " · сообщений сборки: " + diagnostics.size());
        status.setText(summary + " · ревизия " + revision);
    }

    void clear() {
        displayedRevision = -1;
        rows.setItems(java.util.List.of());
        rows.setVisible(false);
        refresh.setEnabled(false);
        status.setText(EntityExplorerAccess.REFUSAL_TEXT);
    }
}
