package org.ip.application.document;

import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ip.service.ReceivingDocumentItemService;
import org.ip.service.ReceivingDocumentService;
import org.ip.service.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceivingDocumentSaveUseCaseTest {

    private final ReceivingDocumentService documentService = mock(ReceivingDocumentService.class);
    private final ReceivingDocumentItemService itemService = mock(ReceivingDocumentItemService.class);
    private final ReceivingDocumentSaveUseCase useCase =
        new ReceivingDocumentSaveUseCase(documentService, itemService);

    @Test
    void rejectsInvalidRowsBeforeSavingHeader() {
        ReceivingDocument header = mock(ReceivingDocument.class);
        List<ReceivingDocumentItem> items = List.of(mock(ReceivingDocumentItem.class));
        when(itemService.validateRows(header, items)).thenReturn(List.of("Duplicate nomenclature"));

        assertThatThrownBy(() -> useCase.save(new ReceivingDocumentSaveCommand(header, items)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Duplicate nomenclature");

        verify(documentService, never()).save(header);
        verify(itemService, never()).replaceAll(header, items);
    }

    @Test
    void validatesSavesReplacesAndReloadsItemsInOrder() {
        ReceivingDocument header = mock(ReceivingDocument.class);
        ReceivingDocument saved = mock(ReceivingDocument.class);
        List<ReceivingDocumentItem> items = List.of(mock(ReceivingDocumentItem.class));
        List<ReceivingDocumentItem> persistedItems = List.of(mock(ReceivingDocumentItem.class));
        when(itemService.validateRows(header, items)).thenReturn(List.of());
        when(documentService.save(header)).thenReturn(saved);
        when(itemService.findByParent(saved)).thenReturn(persistedItems);

        ReceivingDocumentSaveResult result = useCase.save(new ReceivingDocumentSaveCommand(header, items));

        assertThat(result.document()).isSameAs(saved);
        assertThat(result.items()).containsExactlyElementsOf(persistedItems);
        InOrder order = inOrder(itemService, documentService);
        order.verify(itemService).validateRows(header, items);
        order.verify(documentService).save(header);
        order.verify(itemService).replaceAll(saved, items);
        order.verify(itemService).findByParent(saved);
    }
}
