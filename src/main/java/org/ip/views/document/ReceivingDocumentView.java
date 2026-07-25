package org.ip.views.document;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ip.form.builtin.ListForm;
import org.ip.form.coordinator.FormCoordinator;
import org.ip.model.ReceivingDocument;

/**
 * Представление списка приёмно-сдаточных накладных.
 *
 * Metadata-driven подход: шапка (число, дата, цеха) и табличная часть "Позиции"
 * (ReceivingDocumentItem) генерируются из @EntityMetadata/@TableSections —
 * см. ReceivingDocument.java. Ручной ReceivingDocumentForm с руками написанным
 * диалогом добавления позиции (EntityField + BigDecimalField) больше не нужен —
 * этот функционал теперь берёт на себя generic ItemTable.
 */
public class ReceivingDocumentView extends VerticalLayout {

    public ReceivingDocumentView(FormCoordinator coordinator) {
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        ListForm<ReceivingDocument, Long> listForm = coordinator.createListForm(ReceivingDocument.class);

        add(listForm);
    }
}
