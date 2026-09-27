package org.ip.form.coordinator;

import org.ip.model.Workshop;
import org.ipro.crud.EntityCopyService;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.FieldFactory;
import org.ipro.form.config.FormAutoConfiguration;
import org.ipro.form.TableSectionFactory;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.form.coordinator.FormOpenMode;
import org.ipro.form.coordinator.ItemFormAccessBinder;
import org.ipro.form.coordinator.ListFormWrapper;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.spi.FormSettingsStore;
import org.ipro.form.spi.GridViewStore;
import org.ipro.form.spi.WorkspaceGateway;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.RlsUiGate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.core.annotation.MergedAnnotations;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D3.5.3: быстрый unit-уровень для UI-state координатора.
 *
 * <p>Тест утверждает ровно три вещи и не больше: <b>объявление скоупа не сняли</b> —
 * {@code @UIScope} со значением {@code vaadin-ui} стоит на фабричном методе координатора
 * (с D3.5.5 бин регистрируется {@code @Bean}-методом, а классовую аннотацию контейнер не читает);
 * два <b>вручную созданных</b> инстанса не делят поля (по построению тавтология —
 * {@code new} всегда даёт независимые объекты); открытие списка идёт в рабочую область именно этого
 * инстанса, а без рабочей области — отказ с причиной вместо тихо созданной формы.</p>
 *
 * <p>Ни одна из этих проверок не доказывает, что контейнер создаёт отдельный координатор на UI:
 * аннотация на классе игнорируется, если бин зарегистрирован {@code @Bean}-методом, а два
 * {@code new} не могут делить состояние. Настоящее доказательство —
 * {@code org.ip.form.coordinator.FormCoordinatorScopeGuardTest}: он спрашивает скоуп бин-дефиниции
 * в реальном контексте и запрещает синглтонам внедрять UI-scoped бины напрямую. Здесь остаётся
 * дешёвая проверка «объявление не сняли» и поведения навигации.</p>
 */
class FormCoordinatorUiIsolationTest {

    @Test
    void coordinatorIsUiScoped() {
        Method factoryMethod = coordinatorFactoryMethod();

        assertThat(MergedAnnotations.from(factoryMethod).get(Scope.class).getString("value"))
            .as("снятие @UIScope возвращает межсессионный дефект, а не стиль. Ищется он на фабричном"
                + " методе: аннотация класса при @Bean-регистрации не наследуется, поэтому проверять"
                + " класс было бы проверкой комментария")
            .isEqualTo("vaadin-ui");
    }

    /** Фабричный метод координатора: именно его аннотации читает контейнер (D3.5.5). */
    private static Method coordinatorFactoryMethod() {
        for (Method method : FormAutoConfiguration.class.getDeclaredMethods()) {
            if (method.getName().equals("formCoordinator")) {
                return method;
            }
        }
        throw new AssertionError("в FormAutoConfiguration нет фабричного метода formCoordinator:"
            + " координатор перестал быть бином конфигурации, и этот забор стал вакуумным");
    }

    /**
     * Рабочая область приходит из UI-scoped бина, поэтому каждое открытие идёт в свою: у одного
     * инстанса — своя область, у другого — своя, и перепутать их нечем (нет мутабельного
     * {@code setWorkspace}, который писал в общий синглтон).
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void openListFormRoutesToTheWorkspaceOfItsOwnInstance() {
        WorkspaceGateway gatewayA = mock(WorkspaceGateway.class);
        WorkspaceGateway gatewayB = mock(WorkspaceGateway.class);
        FormCoordinator uiA = coordinator(gatewayA);
        FormCoordinator uiB = coordinator(gatewayB);

        uiA.openListForm(Workshop.class, null, null);

        ArgumentCaptor<Consumer> initializer = ArgumentCaptor.forClass(Consumer.class);
        verify(gatewayA).open(eq(ListFormWrapper.class), anyString(), anyString(),
            initializer.capture());
        verifyNoInteractions(gatewayB);
    }

    @Test
    void openListFormWithoutWorkspaceFailsWithReason() {
        FormCoordinator ui = coordinator(null);

        assertThatThrownBy(() -> ui.openListForm(Workshop.class, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("рабочей области");
    }

    @Test
    void openModesOfTwoUisAreIndependent() {
        FormCoordinator uiA = coordinator(null);
        FormCoordinator uiB = coordinator(null);

        uiA.setItemFormOpenMode(FormOpenMode.WORKSPACE_TAB);

        assertThat(uiA.getItemFormOpenMode()).isEqualTo(FormOpenMode.WORKSPACE_TAB);
        assertThat(uiB.getItemFormOpenMode()).isEqualTo(FormOpenMode.DIALOG);
    }

    private static FormCoordinator coordinator(WorkspaceGateway gateway) {
        MetadataResolver metadataResolver = mock(MetadataResolver.class);
        EntityMetadataInfo meta = mock(EntityMetadataInfo.class);
        when(meta.getListFormTitle()).thenReturn("Title");
        when(metadataResolver.resolve(Workshop.class)).thenReturn(meta);
        FormResolver formResolver = mock(FormResolver.class);
        when(formResolver.getFormRegistry()).thenReturn(new FormRegistry());

        @SuppressWarnings("unchecked")
        ObjectProvider<WorkspaceGateway> workspaceGateways = mock(ObjectProvider.class);
        when(workspaceGateways.getIfAvailable()).thenReturn(gateway);

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
            mock(org.ipro.form.action.ActionRegistry.class),
            mock(org.ipro.form.action.ActionContextProvider.class),
            mock(org.ipro.form.action.ActionHandlerRegistry.class),
            mock(FormLinkService.class),
            mock(EntityCopyService.class),
            mock(TableSectionFactory.class),
            workspaceGateways,
            // Мост адреса не участвует в проверке изоляции UI-состояния: этот тест про вкладки
            // двух UI, а не про адрес.
            null);
    }
}
