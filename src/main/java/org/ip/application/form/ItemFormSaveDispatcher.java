package org.ip.application.form;

import org.ip.application.document.ReceivingDocumentFormSaveAdapter;
import org.ip.form.builtin.ItemForm;
import org.ip.model.ReceivingDocument;
import org.ip.service.BaseService;
import org.ipro.crud.IdentifiableEntity;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Selects the save lifecycle for a form. The receiving document uses its
 * aggregate transaction; all other entities retain the existing generic path.
 */
@Component
public class ItemFormSaveDispatcher {

    private final ReceivingDocumentFormSaveAdapter receivingDocumentAdapter;

    public ItemFormSaveDispatcher(ReceivingDocumentFormSaveAdapter receivingDocumentAdapter) {
        this.receivingDocumentAdapter = Objects.requireNonNull(
            receivingDocumentAdapter, "receivingDocumentAdapter must not be null"
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity> T save(ItemForm<T> form, BaseService<T, ?> service) {
        Objects.requireNonNull(form, "form must not be null");
        Objects.requireNonNull(service, "service must not be null");

        if (ReceivingDocument.class.equals(form.getEntityClass())) {
            return (T) receivingDocumentAdapter.save((ItemForm) form);
        }

        T saved = service.save(form.getEntity());
        form.commitTableSections(saved);
        form.commitSnapshot();
        return saved;
    }
}
