package org.ip.application.form;

import org.ipro.crud.BaseService;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.FormSaveHandler;
import org.ipro.form.FormSaveResult;
import org.ipro.form.ItemFormSaveHandler;
import org.ipro.form.ItemFormSaveHandlerRegistry;
import org.ipro.form.MetadataDrivenItemFormSaveAdapter;
import org.ipro.form.builtin.ItemForm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Selects the save lifecycle for a form. Custom application handlers are resolved
 * through {@link ItemFormSaveHandlerRegistry}; if no override exists, every
 * standard entity uses the metadata-driven aggregate adapter. The dispatcher has
 * no knowledge of concrete application/domain types.
 *
 * <p>Implements {@link FormSaveHandler} (спецификация «Часть C.1»): исход
 * возвращается как {@link FormSaveResult}, исключения не подавляются — их
 * оборачивает {@code ItemForm.save()}. Форму/диалог этот обработчик не закрывает.</p>
 */
@Component
public class ItemFormSaveDispatcher implements FormSaveHandler<IdentifiableEntity> {

    private final ItemFormSaveHandlerRegistry handlerRegistry;
    private final MetadataDrivenItemFormSaveAdapter metadataDrivenAdapter;
    private final ServiceLocator serviceLocator;

    @Autowired
    public ItemFormSaveDispatcher(ItemFormSaveHandlerRegistry handlerRegistry,
                                  MetadataDrivenItemFormSaveAdapter metadataDrivenAdapter,
                                  ServiceLocator serviceLocator) {
        this.handlerRegistry = Objects.requireNonNull(
            handlerRegistry, "handlerRegistry must not be null");
        this.metadataDrivenAdapter = metadataDrivenAdapter;
        this.serviceLocator = Objects.requireNonNull(
            serviceLocator, "serviceLocator must not be null");
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public FormSaveResult<IdentifiableEntity> save(ItemForm<IdentifiableEntity> form) {
        Objects.requireNonNull(form, "form must not be null");

        var customHandler = handlerRegistry.find(form.getEntityClass());
        if (customHandler.isPresent()) {
            ItemFormSaveHandler handler = customHandler.get();
            return (FormSaveResult) handler.save((ItemForm) form);
        }

        if (metadataDrivenAdapter != null) {
            return (FormSaveResult) metadataDrivenAdapter.save(form);
        }

        // Transitional hand-built fallback for callers that have not yet wired
        // the platform adapter. It is legal only for a header without sections;
        // attached sections must never return to the old two-phase save path.
        if (!form.attachedSectionClasses().isEmpty()) {
            throw new IllegalStateException(
                "Metadata-driven aggregate adapter is required for forms with attached sections");
        }

        BaseService service = serviceLocator.findService(form.getEntityClass());
        IdentifiableEntity saved = (IdentifiableEntity) service.save(form.getEntity());
        form.commitSnapshot();
        return new FormSaveResult.Success<>(saved);
    }
}
