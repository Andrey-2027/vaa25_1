package org.ipro.form.builtin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.LookupService;
import org.ipro.crud.TableSectionService;
import org.ipro.form.DialogForm;
import org.ipro.form.CommandBar;
import org.ipro.form.FieldFactory;
import org.ipro.form.FormBinding;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Окно строки табличной части на {@link DialogForm}: диалог ввода OK / Отмена (Д-ДФ17). */
class ItemTableRowDialogTest {

    public static class Row extends BaseEntity {
    }

    private final AtomicInteger confirmed = new AtomicInteger();
    private final TextField field = new TextField();
    private ItemTable<Row, BaseEntity> table;
    private final Row row = new Row();
    /** UI.getCurrent() хранится слабой ссылкой: UI держим в поле на время теста. */
    private UI ui;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        ui = new UI();
        UI.setCurrent(ui);
        var session = mock(com.vaadin.flow.server.VaadinSession.class, RETURNS_DEEP_STUBS);
        when(session.getErrorHandler()).thenReturn(null);
        ui.getInternals().setSession(session);

        TableSectionMetadataInfo sectionMeta = mock(TableSectionMetadataInfo.class);
        when(sectionMeta.getRowClass()).thenReturn((Class) Row.class);
        when(sectionMeta.getGridFields()).thenReturn(List.of());
        when(sectionMeta.getFormFields()).thenReturn(List.of());
        table = new ItemTable<>(sectionMeta, mock(FieldFactory.class), mock(TableSectionService.class),
            mock(MetadataResolver.class), null, null, mock(LookupService.class), () -> null);
    }

    @AfterEach
    void detachUi() {
        UI.setCurrent(null);
    }

    private DialogForm<?> openRowDialog() throws Exception {
        Method open = java.util.Arrays.stream(ItemTable.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("openRowDialog")).findFirst().orElseThrow();
        open.setAccessible(true);
        open.invoke(table, row, "Изменить: Материалы", (Runnable) confirmed::incrementAndGet);
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        return descendants(ui).filter(DialogForm.class::isInstance).map(c -> (DialogForm<?>) c)
            .filter(DialogForm::isOpened).findFirst().orElseThrow(() -> new AssertionError("окно строки не открыто"));
    }

    /** Добавляет в форму строки одно текстовое поле, чтобы можно было сделать её «грязной». */
    private void bindFieldTo(DialogForm<?> dialog) {
        ItemForm<?> form = dialog.getBody().getChildren().filter(ItemForm.class::isInstance)
            .map(c -> (ItemForm<?>) c).findFirst().orElseThrow();
        var meta = mock(FieldMetadataInfo.class);
        when(meta.getName()).thenReturn("note");
        form.getBindingRegistry().add(new FormBinding(meta, field, entity -> "", (entity, value) -> { },
            field::getValue, value -> field.setValue((String) value), value -> value == null, field::setReadOnly));
        form.getBindingRegistry().markClean();
    }

    private Optional<ConfirmDialog> question() {
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        return descendants(ui).filter(ConfirmDialog.class::isInstance).map(ConfirmDialog.class::cast)
            .filter(ConfirmDialog::isOpened).findFirst();
    }

    private static Stream<Component> descendants(Component root) {
        return Stream.concat(Stream.of(root), root.getChildren().flatMap(ItemTableRowDialogTest::descendants));
    }

    @Test
    void rowWindowIsInputDialogWithOkAndCancel() throws Exception {
        var dialog = openRowDialog();

        assertThat(dialog.getHeaderTitle()).isEqualTo("Изменить: Материалы");
        assertThat(dialog.getWidth()).isEqualTo("600px");
        assertThat(dialog.isCloseOnOutsideClick()).isFalse();
        assertThat(dialog.getOkButton().getText()).isEqualTo("OK");
        assertThat(dialog.getOkButton().isVisible()).isTrue();
        assertThat(dialog.getCancelButton().getText()).isEqualTo("Отмена");
    }

    @Test
    void savedRowHasNoCommandPanelEvenForAdmin() throws Exception {
        var previous = SecurityContextHolder.getContext();
        var admin = SecurityContextHolder.createEmptyContext();
        admin.setAuthentication(new TestingAuthenticationToken("admin", "", "ROLE_ADMIN"));
        SecurityContextHolder.setContext(admin);
        try {
            row.setId(7L);
            var form = openRowDialog().getBody().getChildren().filter(ItemForm.class::isInstance)
                .findFirst().orElseThrow();
            assertThat(CommandBar.forForm(form).isVisible()).isFalse();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    @Test
    void okConfirmsRowAndClosesWindowBeforeCallback() throws Exception {
        var dialog = openRowDialog();

        dialog.getOkButton().click();

        assertThat(dialog.isOpened()).isFalse();
        assertThat(confirmed).hasValue(1);
    }

    @Test
    void cancelWithoutChangesClosesWithoutQuestionAndConfirmation() throws Exception {
        var dialog = openRowDialog();

        dialog.getCancelButton().click();

        assertThat(dialog.isOpened()).isFalse();
        assertThat(confirmed).hasValue(0);
        assertThat(question()).isEmpty();
    }

    @Test
    void escapeWithChangesAsksAndCloseDiscardsWithoutConfirming() throws Exception {
        var dialog = openRowDialog();
        bindFieldTo(dialog);
        field.setValue("правка");

        dialog.cancel();

        assertThat(dialog.isOpened()).isTrue();
        var asked = question().orElseThrow(() -> new AssertionError("вопрос не открыт"));
        ComponentUtil.fireEvent(asked, new ConfirmDialog.RejectEvent(asked, true));
        assertThat(dialog.isOpened()).isFalse();
        assertThat(confirmed).hasValue(0);
    }

    @Test
    void saveAndCloseInQuestionConfirmsRow() throws Exception {
        var dialog = openRowDialog();
        bindFieldTo(dialog);
        field.setValue("правка");

        dialog.cancel();
        var asked = question().orElseThrow();
        ComponentUtil.fireEvent(asked, new ConfirmDialog.ConfirmEvent(asked, true));

        assertThat(dialog.isOpened()).isFalse();
        assertThat(confirmed).hasValue(1);
    }
}
