package org.ip.application.form;

import org.ip.application.document.ReceivingDocumentFormSaveAdapter;
import org.ip.form.builtin.ItemForm;
import org.ip.model.ReceivingDocument;
import org.ip.service.BaseService;
import org.ip.service.ServiceLocator;
import org.ipro.crud.IdentifiableEntity;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Selects the save lifecycle for a form. The receiving document uses its
 * aggregate transaction; all other entities retain the existing generic path
 * with the service resolved via {@link ServiceLocator}.
 *
 * <p>Implements {@link FormSaveHandler} (спецификация «Часть C.1»): исход
 * возвращается как {@link FormSaveResult}, исключения не подавляются — их
 * оборачивает {@code ItemForm.save()}. Форму/диалог этот обработчик не закрывает.</p>
 */
@Component
public class ItemFormSaveDispatcher implements FormSaveHandler<IdentifiableEntity> {

    private final ReceivingDocumentFormSaveAdapter receivingDocumentAdapter;
    private final ServiceLocator serviceLocator;

    public ItemFormSaveDispatcher(ReceivingDocumentFormSaveAdapter receivingDocumentAdapter,
                                  ServiceLocator serviceLocator) {
        this.receivingDocumentAdapter = Objects.requireNonNull(
            receivingDocumentAdapter, "receivingDocumentAdapter must not be null"
        );
        this.serviceLocator = Objects.requireNonNull(
            serviceLocator, "serviceLocator must not be null"
        );
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public FormSaveResult<IdentifiableEntity> save(ItemForm<IdentifiableEntity> form) {
        Objects.requireNonNull(form, "form must not be null");

        if (ReceivingDocument.class.equals(form.getEntityClass())) {
            ReceivingDocument saved = receivingDocumentAdapter.save((ItemForm) form);
            return new FormSaveResult.Success<>(saved);
        }

        BaseService service = serviceLocator.findService(form.getEntityClass());
        IdentifiableEntity saved = (IdentifiableEntity) service.save(form.getEntity());
        form.commitTableSections(saved);
        form.commitSnapshot();
        return new FormSaveResult.Success<>(saved);
    }
}
