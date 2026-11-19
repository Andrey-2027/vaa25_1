package org.ipro.form.coordinator;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.crud.*;
import org.ipro.form.*;
import org.ipro.form.action.*;
import org.ipro.form.spi.*;
import org.ipro.metadata.*;
import org.ipro.rls.RlsUiGate;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.link.EntityStructureNavigation;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Окно карточки элемента на {@link DialogForm}: закрытие идёт только через общий вопрос о
 * несохранённых изменениях, успешное сохранение закрывает окно до {@code onSaved}.
 */
class ItemFormDialogHostTest {

    public static class Entity extends BaseEntity {
        String title = "";
    }

    private final FormResolver resolver = mock(FormResolver.class);
    private final FormSaveHandler<?> saves = mock(FormSaveHandler.class);
    private final EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
    private final TextField title = new TextField();
    private final List<Entity> saved = new ArrayList<>();
    /** UI.getCurrent() держится слабой ссылкой: без поля UI собирается GC посреди теста. */
    private UI ui;

    @BeforeEach
    void attachUi() {
        ui = new UI();
        UI.setCurrent(ui);
        var session = mock(com.vaadin.flow.server.VaadinSession.class, RETURNS_DEEP_STUBS);
        when(session.getErrorHandler()).thenReturn(null);
        ui.getInternals().setSession(session);
    }

    @AfterEach
    void detachUi() {
        UI.setCurrent(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private FormCoordinator coordinator() {
        doReturnEntity();
        when(metadata.getFormFields()).thenReturn(List.of());
        when(metadata.getGridFields()).thenReturn(List.of());
        when(metadata.getItemFormTitle()).thenReturn("Сущность");
        var metadataResolver = mock(MetadataResolver.class);
        when(metadataResolver.resolve(Entity.class)).thenReturn(metadata);
        when(resolver.getFormRegistry()).thenReturn(new FormRegistry());
        var context = mock(ApplicationContext.class);
        when(context.getBean(FormSaveHandler.class)).thenReturn(saves);
        when(context.getBean(LookupService.class)).thenReturn(mock(LookupService.class));
        var navigation = mock(EntityStructureNavigation.class);
        when(navigation.availability(Entity.class)).thenReturn(EntityStructureNavigation.Availability.allowed());
        when(navigation.link(Entity.class)).thenReturn(Optional.empty());
        var coordinator = new FormCoordinator(metadataResolver, mock(FieldFactory.class), context, resolver,
            mock(ServiceLocator.class), mock(FormSettingsStore.class), mock(GridViewStore.class), mock(RlsUiGate.class),
            mock(ItemFormAccessBinder.class), mock(ActionRegistry.class), mock(ActionContextProvider.class),
            mock(ActionHandlerRegistry.class), mock(FormLinkService.class), mock(EntityCopyService.class),
            mock(TableSectionFactory.class), mock(ObjectProvider.class), null);
        coordinator.setEntityStructureNavigation(navigation);
        return coordinator;
    }

    @SuppressWarnings("unchecked")
    private void doReturnEntity() {
        org.mockito.Mockito.doReturn(Entity.class).when(metadata).getEntityClass();
    }

    /** Новая запись: форма с одним полем «title»; возвращает окно, в котором она открыта. */
    private DialogForm<Entity> openNewCard() {
        var form = new ItemForm<>(Entity.class, List.of(), mock(FieldFactory.class));
        var field = mock(FieldMetadataInfo.class);
        when(field.getName()).thenReturn("title");
        form.getBindingRegistry().add(new FormBinding(field, title, entity -> ((Entity) entity).title,
            (entity, value) -> ((Entity) entity).title = (String) value, title::getValue,
            value -> title.setValue((String) value), value -> value == null, title::setReadOnly));
        org.mockito.Mockito.doReturn(form).when(resolver).resolveItemForm(Entity.class, null, null, null);
        coordinator().openItemForm(Entity.class, null, (Long) null, saved::add);
        return dialogOf(form);
    }

    @SuppressWarnings("unchecked")
    private static DialogForm<Entity> dialogOf(Component form) {
        Component current = form;
        while (current != null && !(current instanceof DialogForm<?>)) {
            current = current.getParent().orElse(null);
        }
        assertThat(current).as("карточка должна лежать в DialogForm").isNotNull();
        return (DialogForm<Entity>) current;
    }

    private void escape(DialogForm<?> dialog) {
        ComponentUtil.fireEvent(dialog, new Dialog.DialogCloseActionEvent(dialog, true));
    }

    private Optional<ConfirmDialog> openedQuestion() {
        // Вопрос добавляется в UI на границе beforeClientResponse — прогоняем её, как это делает запрос.
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        return descendants(ui).filter(ConfirmDialog.class::isInstance).map(ConfirmDialog.class::cast)
            .filter(ConfirmDialog::isOpened).findFirst();
    }

    private static Stream<Component> descendants(Component root) {
        return Stream.concat(Stream.of(root), root.getChildren().flatMap(ItemFormDialogHostTest::descendants));
    }

    // 1 ------------------------------------------------------------------------------------

    @Test
    void cardWindowIsDialogFormWithoutOutsideCloseAndFooter() {
        var dialog = openNewCard();

        assertThat(dialog.isOpened()).isTrue();
        assertThat(dialog.isCloseOnOutsideClick()).isFalse();
        assertThat(dialog.getFooter().getElement().getChildren().count()).isZero();
        assertThat(dialog.getWidth()).isEqualTo("800px");
        assertThat(dialog.getHeight()).isEqualTo("600px");
        assertThat(dialog.getHeaderTitle()).startsWith("Создание: Сущность");
    }

    // 2 ------------------------------------------------------------------------------------

    @Test
    void escapeWithoutChangesClosesWindowWithoutSaving() {
        var dialog = openNewCard();

        escape(dialog);

        assertThat(dialog.isOpened()).isFalse();
        assertThat(saved).isEmpty();
        assertThat(openedQuestion()).isEmpty();
    }

    // 3 ------------------------------------------------------------------------------------

    @Test
    void escapeWithChangesAsksAndKeepsWindowUntilUserDecides() {
        var dialog = openNewCard();
        title.setValue("Несохранённый черновик");

        escape(dialog);

        assertThat(dialog.isOpened()).isTrue();
        var question = openedQuestion().orElseThrow(() -> new AssertionError("вопрос не открыт"));

        // «Отмена» вопроса: окно и правка на месте.
        ComponentUtil.fireEvent(question, new ConfirmDialog.CancelEvent(question, true));
        assertThat(dialog.isOpened()).isTrue();
        assertThat(title.getValue()).isEqualTo("Несохранённый черновик");

        // «Закрыть»: окно закрыто, сохранения не было.
        escape(dialog);
        var again = openedQuestion().orElseThrow();
        ComponentUtil.fireEvent(again, new ConfirmDialog.RejectEvent(again, true));
        assertThat(dialog.isOpened()).isFalse();
        assertThat(saved).isEmpty();
    }

    @Test
    void outsideClickIsNotACloseRequestSoChangesAreNeverLostSilently() {
        var dialog = openNewCard();
        title.setValue("Черновик");

        // Щелчок мимо окна Vaadin не доводит до сервера: closeOnOutsideClick выключен.
        assertThat(dialog.isCloseOnOutsideClick()).isFalse();
        assertThat(dialog.isOpened()).isTrue();
    }

    // 4 ------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void successfulSaveClosesWindowBeforeOnSavedIsCalled() {
        var openedWhenDelivered = new ArrayList<Boolean>();
        var form = new ItemForm<>(Entity.class, List.of(), mock(FieldFactory.class));
        org.mockito.Mockito.doReturn(form).when(resolver).resolveItemForm(Entity.class, null, null, null);
        Entity persisted = new Entity();
        persisted.setId(7L);
        when(((FormSaveHandler) saves).save(any())).thenReturn(new FormSaveResult.Success<>(persisted));
        var holder = new DialogForm[1];

        coordinator().openItemForm(Entity.class, null, (Long) null, result -> {
            openedWhenDelivered.add(holder[0].isOpened());
            saved.add(result);
        });
        holder[0] = dialogOf(form);
        var dialog = holder[0];

        form.getOnSave().run();

        assertThat(dialog.isOpened()).isFalse();
        assertThat(saved).containsExactly(persisted);
        assertThat(openedWhenDelivered).containsExactly(false);
    }

    // 5 ------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void versionConflictKeepsWindowOpenAndChangesInPlace() {
        var form = new ItemForm<>(Entity.class, List.of(), mock(FieldFactory.class));
        var field = mock(FieldMetadataInfo.class);
        when(field.getName()).thenReturn("title");
        form.getBindingRegistry().add(new FormBinding(field, title, entity -> ((Entity) entity).title,
            (entity, value) -> ((Entity) entity).title = (String) value, title::getValue,
            value -> title.setValue((String) value), value -> value == null, title::setReadOnly));
        org.mockito.Mockito.doReturn(form).when(resolver).resolveItemForm(Entity.class, null, null, null);
        when(((FormSaveHandler) saves).save(any()))
            .thenThrow(new ObjectOptimisticLockingFailureException(Entity.class, 1L));

        coordinator().openItemForm(Entity.class, null, (Long) null, saved::add);
        var dialog = dialogOf(form);
        title.setValue("Мои правки");

        form.getOnSave().run();

        assertThat(dialog.isOpened()).isTrue();
        assertThat(saved).isEmpty();
        assertThat(title.getValue()).isEqualTo("Мои правки");
        assertThat(form.isDirty()).isTrue();
    }

    // ДФ-6 ---------------------------------------------------------------------------------

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void saveAndCloseCommandOnThePanelSavesAndClosesWithoutCancelCommand() {
        var form = new ItemForm<>(Entity.class, List.of(), mock(FieldFactory.class));
        org.mockito.Mockito.doReturn(form).when(resolver).resolveItemForm(Entity.class, null, null, null);
        Entity persisted = new Entity();
        persisted.setId(9L);
        when(((FormSaveHandler) saves).save(any())).thenReturn(new FormSaveResult.Success<>(persisted));

        coordinator().openItemForm(Entity.class, null, (Long) null, saved::add);
        var dialog = dialogOf(form);
        var bar = CommandBar.forForm(form);

        assertThat(bar.isVisible()).isTrue();
        assertThat(bar.command(org.ipro.form.action.CrudAction.SAVE.id()).button().getText())
            .isEqualTo("Записать и закрыть");
        assertThat(form.getFooter().getComponentCount()).isZero();
        assertThat(form.getFooter().isVisible()).isFalse();

        bar.command(org.ipro.form.action.CrudAction.SAVE.id()).button().click();

        assertThat(dialog.isOpened()).isFalse();
        assertThat(saved).containsExactly(persisted);
    }

    @Test
    void serviceCommandsLiveInTheMoreMenuOfTheCardWindow() {
        var dialog = openNewCard();
        var form = dialog.getBody().getChildren().filter(ItemForm.class::isInstance)
            .map(ItemForm.class::cast).findFirst().orElseThrow();
        var bar = CommandBar.forForm(form);

        assertThat(bar.hasCommand(org.ipro.form.action.CrudAction.COPY_LINK.id())).isTrue();
        assertThat(bar.hasCommand(org.ipro.form.builder.ListUiCommand.DEVELOPMENT.id())).isTrue();
        assertThat(bar.hasCommand(org.ipro.form.action.ActionId.of("item.history"))).isTrue();
        assertThat(bar.command(org.ipro.form.action.CrudAction.COPY_LINK.id()).button()).isNull();
    }
}
