package org.ip.application.document;

import org.ip.form.builtin.ItemForm;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Bridges the receiving-document form state to the transactional application
 * save operation and re-applies the persisted aggregate to the same form.
 */
@Component
public class ReceivingDocumentFormSaveAdapter {

    private final ReceivingDocumentSaveUseCase saveUseCase;

    public ReceivingDocumentFormSaveAdapter(ReceivingDocumentSaveUseCase saveUseCase) {
        this.saveUseCase = Objects.requireNonNull(saveUseCase, "saveUseCase must not be null");
    }

    public ReceivingDocument save(ItemForm<ReceivingDocument> form) {
        Objects.requireNonNull(form, "form must not be null");

        ReceivingDocument header = form.getEntity();
        List<ReceivingDocumentItem> items = form.getTableSections().stream()
            .flatMap(table -> table.getRows().stream())
            .filter(ReceivingDocumentItem.class::isInstance)
            .map(ReceivingDocumentItem.class::cast)
            .toList();

        ReceivingDocumentSaveResult result = saveUseCase.save(
            new ReceivingDocumentSaveCommand(header, items)
        );
        form.setEntity(result.document());
        form.commitSnapshot();
        return result.document();
    }
}
