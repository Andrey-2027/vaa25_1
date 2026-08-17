package org.ip.application.document;

import jakarta.transaction.Transactional;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ip.service.ReceivingDocumentItemService;
import org.ip.service.ReceivingDocumentService;
import org.ip.service.ValidationException;
import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.core.TelemetryBridge;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Application boundary for atomic persistence of a receiving document aggregate.
 *
 * <p>Validation of the complete table section runs before the header is saved.
 * Any runtime failure while persisting the header or its items marks this
 * transaction for rollback.</p>
 */
@Service
public class ReceivingDocumentSaveUseCase {

    private final ReceivingDocumentService documentService;
    private final ReceivingDocumentItemService itemService;

    public ReceivingDocumentSaveUseCase(ReceivingDocumentService documentService,
                                        ReceivingDocumentItemService itemService) {
        this.documentService = Objects.requireNonNull(documentService, "documentService must not be null");
        this.itemService = Objects.requireNonNull(itemService, "itemService must not be null");
    }

    /**
     * Единственный владелец бизнес-события {@code save:ReceivingDocument}
     * (спецификация «Часть C.2»): скоуп открывается только ПОСЛЕ валидации строк,
     * т.е. событие соответствует попытке сохранения, а UI-слой эмитит лишь
     * намерение {@code ui:save-intent:...}, не дублируя это событие.
     */
    @Transactional(rollbackOn = Exception.class)
    public ReceivingDocumentSaveResult save(ReceivingDocumentSaveCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        ReceivingDocument header = command.header();
        List<ReceivingDocumentItem> items = command.items();
        List<String> errors = itemService.validateRows(header, items);
        if (!errors.isEmpty()) {
            throw new ValidationException(String.join(System.lineSeparator(), errors));
        }

        try (OperationScope scope = TelemetryBridge.beginOperation("save:ReceivingDocument")) {
            ReceivingDocument saved = documentService.save(header);
            itemService.replaceAll(saved, items);
            List<ReceivingDocumentItem> persistedItems = itemService.findByParent(saved);
            return new ReceivingDocumentSaveResult(saved, persistedItems);
        }
    }
}
