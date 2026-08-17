package org.ip.application.document;

import org.ip.form.builtin.ItemForm;
import org.ip.form.builtin.ItemTable;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReceivingDocumentFormSaveAdapterTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void harvestBuildsCommandFromFormState() {
        ItemForm<ReceivingDocument> form = mock(ItemForm.class);
        ItemTable table = mock(ItemTable.class);
        ReceivingDocument header = mock(ReceivingDocument.class);
        ReceivingDocumentItem item = mock(ReceivingDocumentItem.class);
        doReturn(header).when(form).getEntity();
        doReturn(table).when(form).tableSection(ReceivingDocumentItem.class);
        doReturn(List.of(item)).when(table).getRows();

        ReceivingDocumentSaveCommand command =
            new ReceivingDocumentFormSaveAdapter(mock(ReceivingDocumentSaveUseCase.class)).harvest(form);

        assertThat(command.header()).isSameAs(header);
        assertThat(command.items()).containsExactly(item);
        verify(form).tableSection(ReceivingDocumentItem.class);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void applyReappliesHeaderAndRowsWithoutTableReload() {
        ItemForm<ReceivingDocument> form = mock(ItemForm.class);
        ItemTable table = mock(ItemTable.class);
        ReceivingDocument saved = mock(ReceivingDocument.class);
        ReceivingDocumentItem item = mock(ReceivingDocumentItem.class);
        doReturn(table).when(form).tableSection(ReceivingDocumentItem.class);

        new ReceivingDocumentFormSaveAdapter(mock(ReceivingDocumentSaveUseCase.class))
            .apply(form, new ReceivingDocumentSaveResult(saved, List.of(item)));

        verify(form).applyPersistedEntity(saved);
        verify(table).applyPersistedRows(saved, List.of(item));
        verify(form).commitSnapshot();
        // старый путь setEntity (с table reload) не используется
        verify(form, never()).setEntity(any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void saveHarvestsDelegatesToUseCaseAndReappliesPersistedDocument() {
        ReceivingDocumentSaveUseCase useCase = mock(ReceivingDocumentSaveUseCase.class);
        ItemForm<ReceivingDocument> form = mock(ItemForm.class);
        ItemTable table = mock(ItemTable.class);
        ReceivingDocument header = mock(ReceivingDocument.class);
        ReceivingDocument saved = mock(ReceivingDocument.class);
        ReceivingDocumentItem item = mock(ReceivingDocumentItem.class);
        doReturn(header).when(form).getEntity();
        doReturn(table).when(form).tableSection(ReceivingDocumentItem.class);
        doReturn(List.of(item)).when(table).getRows();
        when(useCase.save(any(ReceivingDocumentSaveCommand.class)))
            .thenReturn(new ReceivingDocumentSaveResult(saved, List.of(item)));

        ReceivingDocument result = new ReceivingDocumentFormSaveAdapter(useCase).save(form);

        assertThat(result).isSameAs(saved);
        verify(useCase).save(new ReceivingDocumentSaveCommand(header, List.of(item)));
        verify(form).applyPersistedEntity(saved);
        verify(table).applyPersistedRows(saved, List.of(item));
        verify(form).commitSnapshot();
    }
}
