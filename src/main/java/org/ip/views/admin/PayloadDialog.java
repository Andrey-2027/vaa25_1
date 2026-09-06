package org.ip.views.admin;

import org.ipro.telemetry.core.JournalQueryService;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.icon.VaadinIcon;

/**
 * Диалог «Дерево операции»: обёртка над TelemetryUi.openPayloadDialog,
 * единая точка вызова из панелей журнала/ошибок/трасс.
 */
final class PayloadDialog {

    private PayloadDialog() {
    }

    static void open(JournalQueryService journal, long eventId) {
        TelemetryUi.openPayloadDialog(journal, eventId);
    }

    static Button openButton(JournalQueryService journal, long eventId) {
        Button button = new Button(VaadinIcon.EYE.create());
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        button.setTooltipText("Дерево операции");
        button.addClickListener(e -> open(journal, eventId));
        return button;
    }
}
