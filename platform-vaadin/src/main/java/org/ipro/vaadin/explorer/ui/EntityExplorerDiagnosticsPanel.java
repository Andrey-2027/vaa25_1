package org.ipro.vaadin.explorer.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.*;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import java.util.Objects;

/** Common, unassigned messages from the supplied snapshot. The host owns its working tab. */
@StyleSheet("context://VAADIN/static/ipro/entity-explorer-registry.css")
public final class EntityExplorerDiagnosticsPanel extends Div {
    public EntityExplorerDiagnosticsPanel(ExplorerSnapshot snapshot) {
        addClassName("ipro-explorer-diagnostics"); setSnapshot(snapshot);
    }
    public void setSnapshot(ExplorerSnapshot snapshot) {
        Objects.requireNonNull(snapshot); removeAll();
        add(new H3("Общие диагностики"), new Paragraph("Сообщений: " + snapshot.unassignedDiagnostics().size()));
        if (!snapshot.diagnosticsAvailable()) add(new Paragraph("Полная диагностика недоступна; отсутствие сообщений не означает отсутствие ошибок."));
        for (var row : snapshot.unassignedDiagnostics()) {
            String severity = switch (row.severity()) { case ERROR -> "Ошибка"; case WARNING -> "Предупреждение"; default -> "Информация"; };
            var message = new Div(new H4(severity + " · " + row.code()), new Paragraph(row.caption()), new Paragraph(row.value().value()));
            message.addClassName("er-diagnostic-message"); message.getElement().setAttribute("data-severity", row.severity().name()); add(message);
        }
    }
}
