package org.ipro.form.coordinator;

import com.vaadin.flow.component.UI;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.FieldFactory;
import org.ipro.form.FormSaveHandler;
import org.ipro.form.FormSaveResult;
import org.ipro.form.action.ActionContextProvider;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.registry.FormResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemFormWrapperViewSaveTest {
    private UI ui; // CurrentInstance uses a weak reference; retain the UI during the test.
    public static class Document extends BaseEntity { }

    @AfterEach
    void clearUi() { UI.setCurrent(null); }

    @Test
    void workspaceSaveDeliversTypedPersistedEntityToCallback() {
        Document saved = new Document();
        saved.setId(42L);
        AtomicReference<Document> callback = new AtomicReference<>();
        ItemFormWrapperView wrapper = wrapper(new FormSaveResult.Success<>(saved), callback);
        assertThat(wrapper.doSave()).isTrue();
        assertThat(callback.get()).isSameAs(saved);
        assertThat(callback.get().getId()).isEqualTo(42L);
    }

    @Test
    void failedSaveKeepsWorkspaceOpenAndDoesNotInvokeCallback() {
        AtomicReference<Document> callback = new AtomicReference<>();
        ItemFormWrapperView wrapper = wrapper(new FormSaveResult.Failure<>(List.of("veto"), null), callback);
        assertThat(wrapper.doSave()).isFalse();
        assertThat(callback.get()).isNull();
    }

    @Test
    void versionConflictKeepsWorkspaceOpenAndDoesNotInvokeCallback() {
        AtomicReference<Document> callback = new AtomicReference<>();
        ItemFormWrapperView wrapper = wrapper(new FormSaveResult.Conflict<>(List.of("stale"), null), callback);
        assertThat(wrapper.doSave()).isFalse();
        assertThat(callback.get()).isNull();
    }

    private ItemFormWrapperView wrapper(FormSaveResult<Document> result,
            AtomicReference<Document> callback) {
        ui = new UI(); UI.setCurrent(ui);
        ItemForm<Document> form = new ItemForm<>(Document.class, List.of(), mock(FieldFactory.class));
        FormResolver resolver = mock(FormResolver.class);
        when(resolver.resolveItemForm(Document.class, "variant", null, null)).thenReturn(form);
        ApplicationContext context = mock(ApplicationContext.class);
        FormSaveHandler<Document> handler = ignored -> result;
        when(context.getBean(FormSaveHandler.class)).thenReturn(handler);
        ItemFormWrapperView wrapper = new ItemFormWrapperView(context, resolver, mock(ServiceLocator.class),
            mock(ItemFormAccessBinder.class), mock(ActionRegistry.class), mock(ActionContextProvider.class),
            ActionHandlerRegistry.empty(), mock(FormLinkService.class));
        wrapper.init(Document.class, "variant", null, callback::set, () -> { });
        return wrapper;
    }
}
