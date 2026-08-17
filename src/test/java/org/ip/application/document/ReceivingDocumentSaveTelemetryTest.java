package org.ip.application.document;

import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ip.service.ReceivingDocumentItemService;
import org.ip.service.ReceivingDocumentService;
import org.ip.service.ValidationException;
import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.api.Telemetry;
import org.ipro.telemetry.core.TelemetryBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Telemetry-owner (спецификация «Часть C.2»): ровно одно бизнес-событие
 * {@code save:ReceivingDocument} на успешное сохранение; при ошибке валидации
 * событие не эмитится вовсе. UI-слой (ItemForm.doSave) эмитит только
 * {@code ui:save-intent:<entity>} — см. ItemFormSaveFlowTest.
 */
@ExtendWith(MockitoExtension.class)
class ReceivingDocumentSaveTelemetryTest {

    private final ReceivingDocumentService documentService = mock(ReceivingDocumentService.class);
    private final ReceivingDocumentItemService itemService = mock(ReceivingDocumentItemService.class);
    private final ReceivingDocumentSaveUseCase useCase =
        new ReceivingDocumentSaveUseCase(documentService, itemService);

    private final Telemetry telemetry = mock(Telemetry.class);

    @BeforeEach
    void installBridge() {
        TelemetryBridge.set(telemetry);
    }

    @AfterEach
    void resetBridge() {
        TelemetryBridge.set(null);
    }

    @Test
    void successfulSaveEmitsExactlyOneBusinessScope() {
        when(telemetry.beginOperation("save:ReceivingDocument"))
            .thenReturn(mock(OperationScope.class));
        ReceivingDocument header = mock(ReceivingDocument.class);
        List<ReceivingDocumentItem> items = List.of(mock(ReceivingDocumentItem.class));
        when(itemService.validateRows(header, items)).thenReturn(List.of());
        ReceivingDocument saved = mock(ReceivingDocument.class);
        when(documentService.save(header)).thenReturn(saved);
        when(itemService.findByParent(saved)).thenReturn(List.of());

        useCase.save(new ReceivingDocumentSaveCommand(header, items));
        useCase.save(new ReceivingDocumentSaveCommand(header, items));

        verify(telemetry, times(2)).beginOperation("save:ReceivingDocument");
    }

    @Test
    void failedValidationEmitsNoBusinessScope() {
        when(telemetry.beginOperation("save:ReceivingDocument"))
            .thenReturn(mock(OperationScope.class));
        ReceivingDocument header = mock(ReceivingDocument.class);
        List<ReceivingDocumentItem> items = List.of(mock(ReceivingDocumentItem.class));
        when(itemService.validateRows(header, items)).thenReturn(List.of("Duplicate nomenclature"));

        assertThatThrownBy(() -> useCase.save(new ReceivingDocumentSaveCommand(header, items)))
            .isInstanceOf(ValidationException.class);

        verify(telemetry, never()).beginOperation(anyString());
    }
}
