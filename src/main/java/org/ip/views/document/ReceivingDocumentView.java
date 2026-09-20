package org.ip.views.document;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.coordinator.FormNavigator;
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

    public ReceivingDocumentView(FormNavigator navigator) {
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        ListForm<ReceivingDocument, Long> listForm = navigator.createListForm(ReceivingDocument.class);

        add(listForm);
    }
}
