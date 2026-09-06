package org.ip.views.admin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;

/** Общие хелперы UI диагностики: время, цвета уровней, диалог payload. */
final class TelemetryUi {

    static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private TelemetryUi() {
    }

    static String formatTime(Instant instant) {
        return instant == null ? "" : TIME.format(instant.atZone(ZoneId.systemDefault()));
    }

    /** CSS-класс уровня журнала для цветовой разметки (см. telemetry-grid.css). */
    static String levelClass(String level) {
        if (level == null) {
            return "tl-level-info";
        }
        return switch (level.toUpperCase()) {
            case "ERROR" -> "tl-level-error";
            case "WARN" -> "tl-level-warn";
            default -> "tl-level-info";
        };
    }

    /** Короткий traceId для показа в гриде. */
    static String shortTrace(String traceId) {
        return traceId != null && traceId.length() > 12
                ? traceId.substring(0, 12) + "…"
                : traceId;
    }

    /** Полный traceId без сокращения (для тултипов). */
    static String fullTrace(String traceId) {
        return traceId == null ? "" : traceId;
    }

    /** Диалог с деревом операции (переиспользует PayloadTreeView журнала). */
    static void openPayloadDialog(org.ipro.telemetry.core.JournalQueryService journal, long id) {
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
}
