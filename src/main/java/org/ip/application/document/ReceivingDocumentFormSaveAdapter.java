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
 *
 * The only place that knows both {@link ItemForm} and the save command/use case:
 * harvest() builds the command from the form, apply() re-applies the persisted
 * aggregate (header via applyPersistedEntity, rows via applyPersistedRows —
 * without a second DB reload that would drop the use case's state).
 */
@Component
public class ReceivingDocumentFormSaveAdapter {

    private final ReceivingDocumentSaveUseCase saveUseCase;

    public ReceivingDocumentFormSaveAdapter(ReceivingDocumentSaveUseCase saveUseCase) {
        this.saveUseCase = Objects.requireNonNull(saveUseCase, "saveUseCase must not be null");
    }

    /**
     * Собрать command из текущего состояния формы: шапка + строки табличной части
     * (уже immutable List.copyOf — ItemTable.getRows()).
     */
    public ReceivingDocumentSaveCommand harvest(ItemForm<ReceivingDocument> form) {
        Objects.requireNonNull(form, "form must not be null");
        ReceivingDocument header = form.getEntity();
        List<ReceivingDocumentItem> items = form.tableSection(ReceivingDocumentItem.class).getRows();
        return new ReceivingDocumentSaveCommand(header, items);
    }

    /**
     * Применить сохранённый агрегат обратно в форму: шапка без table reload,
     * строки через applyPersistedRows (с проставленными id), точка отсчёта dirty — заново.
     */
    public void apply(ItemForm<ReceivingDocument> form, ReceivingDocumentSaveResult result) {
        Objects.requireNonNull(form, "form must not be null");
        Objects.requireNonNull(result, "result must not be null");
        form.applyPersistedEntity(result.document());
        form.tableSection(ReceivingDocumentItem.class)
            .applyPersistedRows(result.document(), result.items());
        form.commitSnapshot();
    }

    public ReceivingDocument save(ItemForm<ReceivingDocument> form) {
        ReceivingDocumentSaveResult result = saveUseCase.save(harvest(form));
        apply(form, result);
        return result.document();
    }
}
