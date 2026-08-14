package org.ip.application.form;

import org.ip.application.document.ReceivingDocumentFormSaveAdapter;
import org.ip.form.builtin.ItemForm;
import org.ip.model.ReceivingDocument;
import org.ip.model.Workshop;
import org.ip.service.BaseService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemFormSaveDispatcherTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void delegatesReceivingDocumentToTransactionalAdapter() {
        ReceivingDocumentFormSaveAdapter adapter = mock(ReceivingDocumentFormSaveAdapter.class);
        ItemForm<ReceivingDocument> form = mock(ItemForm.class);
        BaseService<ReceivingDocument, ?> service = mock(BaseService.class);
        ReceivingDocument saved = mock(ReceivingDocument.class);
        doReturn(ReceivingDocument.class).when(form).getEntityClass();
        when(adapter.save(form)).thenReturn(saved);

        ReceivingDocument result = new ItemFormSaveDispatcher(adapter).save(form, service);

        assertThat(result).isSameAs(saved);
        verify(adapter).save(form);
        verify(service, never()).save(form.getEntity());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void retainsGenericSaveLifecycleForOtherEntities() {
        ReceivingDocumentFormSaveAdapter adapter = mock(ReceivingDocumentFormSaveAdapter.class);
        ItemForm<Workshop> form = mock(ItemForm.class);
        BaseService<Workshop, ?> service = mock(BaseService.class);
        Workshop entity = mock(Workshop.class);
        Workshop saved = mock(Workshop.class);
        doReturn(Workshop.class).when(form).getEntityClass();
        when(form.getEntity()).thenReturn(entity);
        when(service.save(entity)).thenReturn(saved);

        Workshop result = new ItemFormSaveDispatcher(adapter).save(form, service);

        assertThat(result).isSameAs(saved);
        verify(service).save(entity);
        verify(form).commitTableSections(saved);
        verify(form).commitSnapshot();
        verify(adapter, never()).save((ItemForm<ReceivingDocument>) (ItemForm) form);
    }
}
