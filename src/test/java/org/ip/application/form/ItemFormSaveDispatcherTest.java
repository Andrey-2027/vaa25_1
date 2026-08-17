package org.ip.application.form;

import org.ip.application.document.ReceivingDocumentFormSaveAdapter;
import org.ip.form.builtin.ItemForm;
import org.ip.model.ReceivingDocument;
import org.ip.model.Workshop;
import org.ip.service.BaseService;
import org.ip.service.ServiceLocator;
import org.ipro.crud.IdentifiableEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemFormSaveDispatcherTest {

    private final ReceivingDocumentFormSaveAdapter adapter = mock(ReceivingDocumentFormSaveAdapter.class);
    private final ServiceLocator serviceLocator = mock(ServiceLocator.class);
    private final ItemFormSaveDispatcher dispatcher =
        new ItemFormSaveDispatcher(adapter, serviceLocator);

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void delegatesReceivingDocumentToTransactionalAdapter() {
        ItemForm<ReceivingDocument> form = mock(ItemForm.class);
        doReturn(ReceivingDocument.class).when(form).getEntityClass();
        ReceivingDocument saved = mock(ReceivingDocument.class);
        when(adapter.save(form)).thenReturn(saved);

        FormSaveResult<IdentifiableEntity> result = dispatcher.save((ItemForm) form);

        assertThat(result.success()).isTrue();
        assertThat(((FormSaveResult.Success) result).saved()).isSameAs(saved);
        verify(adapter).save(form);
        verify(serviceLocator, never()).findService(any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void retainsGenericSaveLifecycleForOtherEntities() {
        ItemForm<Workshop> form = mock(ItemForm.class);
        doReturn(Workshop.class).when(form).getEntityClass();
        Workshop entity = mock(Workshop.class);
        Workshop saved = mock(Workshop.class);
        when(form.getEntity()).thenReturn(entity);
        BaseService service = mock(BaseService.class);
        when(serviceLocator.findService(Workshop.class)).thenReturn(service);
        when(service.save(entity)).thenReturn(saved);

        FormSaveResult<IdentifiableEntity> result = dispatcher.save((ItemForm) form);

        assertThat(result.success()).isTrue();
        assertThat(((FormSaveResult.Success) result).saved()).isSameAs(saved);
        verify(service).save(entity);
        verify(form).commitTableSections(saved);
        verify(form).commitSnapshot();
        verify(adapter, never()).save((ItemForm<ReceivingDocument>) (ItemForm) form);
    }
}
