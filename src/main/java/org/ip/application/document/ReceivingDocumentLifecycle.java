package org.ip.application.document;

import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ipro.crud.ValidationException;
import org.ipro.lifecycle.AggregateSaveContext;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntitySaveContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Каноническая lifecycle-точка приёмно-сдаточной накладной.
 *
 * <p>Правила шапки находятся в {@link #beforeSave(EntitySaveContext)}, правила
 * шапки вместе с owned rows — в {@link #beforeAggregateSave(AggregateSaveContext)}.
 * Один typed handler заменяет два технических Spring event listeners и применяется
 * одинаково из generic form, REST/import и aggregate use case.</p>
 */
@Component
public class ReceivingDocumentLifecycle implements EntityLifecycle<ReceivingDocument> {

    @Override
    public Class<ReceivingDocument> entityType() {
        return ReceivingDocument.class;
    }

    /** Один и тот же цех не может быть принимающим и передающим. */
    @Override
    public void beforeSave(EntitySaveContext<ReceivingDocument> context) {
        ReceivingDocument document = context.entity();
        if (document.getReceivingWorkshop() != null
            && document.getTransferringWorkshop() != null
            && samePersistedWorkshop(document)) {
            throw new ValidationException(
                "Цех-приемщик и цех-сдатчик не могут быть одинаковыми");
        }
    }

    private boolean samePersistedWorkshop(ReceivingDocument document) {
        Long receivingId = document.getReceivingWorkshop().getId();
        Long transferringId = document.getTransferringWorkshop().getId();
        return receivingId != null && Objects.equals(receivingId, transferringId);
    }

    /** Одна и та же номенклатура не должна встречаться дважды в секции документа. */
    @Override
    public void beforeAggregateSave(
            AggregateSaveContext<ReceivingDocument> context) {
        List<String> errors = new ArrayList<>();
        Set<Long> seenNomenclatureIds = new HashSet<>();

        for (ReceivingDocumentItem item : context.section(ReceivingDocumentItem.class)) {
            if (item == null || item.getNomenclature() == null) {
                continue;
            }
            Long nomenclatureId = item.getNomenclature().getId();
            if (nomenclatureId == null) {
                continue;
            }
            if (!seenNomenclatureIds.add(nomenclatureId)) {
                errors.add("Позиции: номенклатура \""
                    + item.getNomenclature().getName()
                    + "\" указана в накладной более одного раза");
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(String.join(System.lineSeparator(), errors));
        }
    }
}
