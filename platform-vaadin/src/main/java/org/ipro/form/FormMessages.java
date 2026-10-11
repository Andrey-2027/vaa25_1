package org.ipro.form;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Paragraph;
import org.ipro.form.coordinator.AccessDenialPresentation;
import org.ipro.metadata.MetadataResolver;

/** User-readable failure with the standard copy affordance; diagnostics remain server-owned. */
public final class FormMessages {
    private FormMessages() { }
    public static void showError(String message) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Не удалось выполнить действие");
        dialog.setMaxWidth("700px");
        dialog.add(new Paragraph(message));
        dialog.getFooter().add(AccessDenialPresentation.copyMessageButton(message),
            new Button("Закрыть", event -> dialog.close()));
        dialog.open();
    }
    public static boolean showAccessDenied(Throwable failure, String action, MetadataResolver metadata) {
        if (AccessDenialPresentation.find(failure) == null) return false;
        AccessDenialPresentation.show(AccessDenialPresentation.describe(failure, action, metadata));
        return true;
    }
}
