package org.ipro.form.coordinator;

import com.vaadin.flow.spring.annotation.UIScope;
import org.ip.model.Workshop;
import org.ipro.crud.EntityCopyService;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.FieldFactory;
import org.ipro.form.TableSectionFactory;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.spi.FormSettingsStore;
import org.ipro.form.spi.GridViewStore;
import org.ipro.form.spi.ListFormToolbarContributor;
import org.ipro.form.spi.WorkspaceGateway;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.RlsUiGate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.core.annotation.MergedAnnotations;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D3.5.3: UI-state координатора изолировано по UI.
 *
 * <p>До исправления {@code FormCoordinator} был singleton с мутабельными
 * {@code workspace}/{@code itemFormOpenMode}: параллельные пользователи через
 * {@code setWorkspace} из prototype-view перезаписывали чужой Workspace.
 * Теперь бин UI-scoped (декларация проверяется ниже), а этот тест доказывает
 * вторую половину: два инстанса не делят состояние. Полный two-UI Spring-тест
 * требует поднятой Vaadin-сессии; связка «декларация + изоляция инстансов»
 * закрывает риск без session plumbing.</p>
 */
class FormCoordinatorUiIsolationTest {

    @Test
    void coordinatorIsUiScoped() {
        assertThat(FormCoordinator.class.getAnnotation(UIScope.class))
            .as("снятие @UIScope возвращает межсессионный дефект: это забор, а не стиль")
            .isNotNull();
        String scope = MergedAnnotations.from(FormCoordinator.class)
            .get(Scope.class).getString("value");
        assertThat(scope).isEqualTo("vaadin-ui");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void workspacesOfTwoUisDoNotOverwriteEachOther() {
        FormCoordinator uiA = coordinator();
        FormCoordinator uiB = coordinator();
        WorkspaceGateway gatewayA = mock(WorkspaceGateway.class);
        WorkspaceGateway gatewayB = mock(WorkspaceGateway.class);
        uiA.setWorkspace(gatewayA);
        uiB.setWorkspace(gatewayB);

        uiA.openListForm(Workshop.class, null, null);

        ArgumentCaptor<Consumer> initializer = ArgumentCaptor.forClass(Consumer.class);
        verifyNavigationWentTo(gatewayA, initializer);
        verifyNoInteractions(gatewayB);
    }

    @Test
    void openModesOfTwoUisAreIndependent() {
        FormCoordinator uiA = coordinator();
        FormCoordinator uiB = coordinator();

        uiA.setItemFormOpenMode(FormOpenMode.WORKSPACE_TAB);

        assertThat(uiA.getItemFormOpenMode()).isEqualTo(FormOpenMode.WORKSPACE_TAB);
        assertThat(uiB.getItemFormOpenMode()).isEqualTo(FormOpenMode.DIALOG);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void verifyNavigationWentTo(WorkspaceGateway gateway,
                                               ArgumentCaptor<Consumer> initializer) {
        verify(gateway).open(
            eq(ListFormWrapper.class),
            anyString(),
            anyString(),
            initializer.capture());
    }

    private static FormCoordinator coordinator() {
        MetadataResolver metadataResolver = mock(MetadataResolver.class);
        EntityMetadataInfo meta = mock(EntityMetadataInfo.class);
        when(meta.getListFormTitle()).thenReturn("Title");
        when(metadataResolver.resolve(Workshop.class)).thenReturn(meta);
        FormResolver formResolver = mock(FormResolver.class);
        when(formResolver.getFormRegistry()).thenReturn(new FormRegistry());
        return new FormCoordinator(
            metadataResolver,
            mock(FieldFactory.class),
            mock(ApplicationContext.class),
            formResolver,
            mock(ServiceLocator.class),
            mock(FormSettingsStore.class),
            mock(GridViewStore.class),
            mock(RlsUiGate.class),
            mock(ItemFormAccessBinder.class),
            mock(EntityCopyService.class),
            mock(TableSectionFactory.class),
            List.<ListFormToolbarContributor>of());
    }
}
