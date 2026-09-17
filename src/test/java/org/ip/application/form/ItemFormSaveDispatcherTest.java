package org.ip.application.form;

import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ip.model.Workshop;
import org.ipro.crud.BaseService;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.FormSaveResult;
import org.ipro.form.ItemFormSaveHandler;
import org.ipro.form.ItemFormSaveHandlerRegistry;
import org.ipro.form.MetadataDrivenItemFormSaveAdapter;
import org.ipro.form.builtin.ItemForm;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemFormSaveDispatcherTest {

    private final ServiceLocator serviceLocator = mock(ServiceLocator.class);
    private final MetadataDrivenItemFormSaveAdapter metadataAdapter =
        mock(MetadataDrivenItemFormSaveAdapter.class);
    private final ItemFormSaveDispatcher dispatcher = new ItemFormSaveDispatcher(
        ItemFormSaveHandlerRegistry.empty(), metadataAdapter, serviceLocator);

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void delegatesDeclaredCustomOverrideWithoutKnowingItsDomainType() {
        ReceivingDocumentHandler customHandler = new ReceivingDocumentHandler();
        ItemFormSaveDispatcher customDispatcher = new ItemFormSaveDispatcher(
            new ItemFormSaveHandlerRegistry(List.of(customHandler)), metadataAdapter, serviceLocator);
        ItemForm<ReceivingDocument> form = mock(ItemForm.class);
        doReturn(ReceivingDocument.class).when(form).getEntityClass();
        ReceivingDocument saved = mock(ReceivingDocument.class);
        customHandler.saved = saved;

        FormSaveResult<IdentifiableEntity> result = customDispatcher.save((ItemForm) form);

        assertThat(result.success()).isTrue();
        assertThat(((FormSaveResult.Success) result).saved()).isSameAs(saved);
        verify(metadataAdapter, never()).save((ItemForm) form);
        verify(serviceLocator, never()).findService(any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void usesMetadataDrivenAdapterForStandardPilotAndFallbackEntities() {
        ItemForm<PrdSpec> form = mock(ItemForm.class);
        doReturn(PrdSpec.class).when(form).getEntityClass();
        PrdSpec saved = mock(PrdSpec.class);
        FormSaveResult<PrdSpec> expected = new FormSaveResult.Success<>(saved);
        doReturn(expected).when(metadataAdapter).save(form);

        FormSaveResult<IdentifiableEntity> result = dispatcher.save((ItemForm) form);

        assertThat(result).isSameAs(expected);
        verify(metadataAdapter).save(form);
        verify(serviceLocator, never()).findService(any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void retainsHeaderOnlyFallbackForHandBuiltCallersWithoutPlatformAdapter() {
        ItemFormSaveDispatcher legacyDispatcher = new ItemFormSaveDispatcher(
            ItemFormSaveHandlerRegistry.empty(), null, serviceLocator);
        ItemForm<Workshop> form = mock(ItemForm.class);
        doReturn(Workshop.class).when(form).getEntityClass();
        doReturn(Set.of()).when(form).attachedSectionClasses();
        Workshop entity = mock(Workshop.class);
        Workshop saved = mock(Workshop.class);
        when(form.getEntity()).thenReturn(entity);
        BaseService service = mock(BaseService.class);
        when(serviceLocator.findService(Workshop.class)).thenReturn(service);
        when(service.save(entity)).thenReturn(saved);

        FormSaveResult<IdentifiableEntity> result = legacyDispatcher.save((ItemForm) form);

        assertThat(result.success()).isTrue();
        assertThat(((FormSaveResult.Success) result).saved()).isSameAs(saved);
        verify(service).save(entity);
        verify(form).commitSnapshot();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void neverReturnsAttachedSectionsToTwoPhaseFallback() {
        ItemFormSaveDispatcher legacyDispatcher = new ItemFormSaveDispatcher(
            ItemFormSaveHandlerRegistry.empty(), null, serviceLocator);
        ItemForm<PrdSpec> form = mock(ItemForm.class);
        doReturn(PrdSpec.class).when(form).getEntityClass();
        doReturn(Set.of(org.ip.model.PrdSpecOper.class)).when(form).attachedSectionClasses();

        assertThatThrownBy(() -> legacyDispatcher.save((ItemForm) form))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Metadata-driven aggregate adapter");
        verify(serviceLocator, never()).findService(any());
    }

    private static final class ReceivingDocumentHandler
            implements ItemFormSaveHandler<ReceivingDocument> {
        private ReceivingDocument saved;

        @Override
        public Class<ReceivingDocument> supportedEntityClass() {
            return ReceivingDocument.class;
        }

        @Override
        public FormSaveResult<ReceivingDocument> save(ItemForm<ReceivingDocument> form) {
            return new FormSaveResult.Success<>(saved);
        }
    }
}
