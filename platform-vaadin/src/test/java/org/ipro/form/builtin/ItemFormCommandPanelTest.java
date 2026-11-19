package org.ipro.form.builtin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import org.ipro.crud.BaseEntity;
import org.ipro.form.CommandBar;
import org.ipro.form.FieldFactory;
import org.ipro.form.action.ActionId;
import org.ipro.form.action.CrudAction;
import org.ipro.metadata.EntityMetadataInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Командная панель карточки (Д-ДФ10): состав, порядок, режим просмотра, подвал. */
class ItemFormCommandPanelTest {

    public static class TestDocument extends BaseEntity {
    }

    private static ItemForm<TestDocument> form() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestDocument.class).when(metadata).getEntityClass();
        when(metadata.getFormFields()).thenReturn(List.of());
        return new ItemForm<>(metadata, mock(FieldFactory.class));
    }

    private static List<String> panelLabels(CommandBar bar) {
        var row = bar.getChildren().findFirst().orElseThrow();
        return row.getChildren().map(c -> ((Button) c).getElement().getAttribute("aria-label")).toList();
    }

    @Test
    void panelIsFirstChildAndHiddenWhileThereIsNoVisibleCommand() {
        var form = form();
        CommandBar bar = CommandBar.forForm(form);

        assertThat(form.getChildren().findFirst().orElseThrow()).isSameAs(bar);
        // Команда «История» зарегистрирована, но скрыта (нет записи/не админ) — панель не нужна.
        assertThat(bar.hasCommand(ActionId.of("item.history"))).isTrue();
        assertThat(bar.isVisible()).isFalse();
    }

    @Test
    void defaultCommandsShowSaveAndCloseFirstEvenBeforeCustomizerCommands() {
        var form = form();
        form.addCommand("reports", "Отчёты", VaadinIcon.PRINT, () -> { });
        form.withDefaultCommands();
        form.addCommand("later", "Позже", VaadinIcon.PLUS, () -> { });
        CommandBar bar = CommandBar.forForm(form);

        assertThat(bar.isVisible()).isTrue();
        assertThat(panelLabels(bar)).containsExactly("Записать и закрыть", "Отчёты", "Позже");
        assertThat(bar.command(CrudAction.SAVE.id()).button().getText()).isEqualTo("Записать и закрыть");
    }

    @Test
    void readOnlyHidesSaveAndCloseInAnyOrderAndHidesTheEmptyPanel() {
        var form = form();
        form.withDefaultCommands();
        CommandBar bar = CommandBar.forForm(form);

        form.setReadOnly(true);
        assertThat(bar.command(CrudAction.SAVE.id()).isVisible()).isFalse();
        assertThat(bar.isVisible()).isFalse();

        form.setReadOnly(false);
        assertThat(bar.command(CrudAction.SAVE.id()).isVisible()).isTrue();
        assertThat(bar.isVisible()).isTrue();

        var other = form();
        other.setReadOnly(true);
        other.withDefaultCommands();
        assertThat(CommandBar.forForm(other).command(CrudAction.SAVE.id()).isVisible()).isFalse();
    }

    @Test
    void readOnlyKeepsPanelWhenOtherCommandsStayVisible() {
        var form = form();
        form.addCommand("reports", "Отчёты", VaadinIcon.PRINT, () -> { });
        form.withDefaultCommands();

        form.setReadOnly(true);

        assertThat(CommandBar.forForm(form).isVisible()).isTrue();
        assertThat(panelLabels(CommandBar.forForm(form))).contains("Отчёты");
    }

    @Test
    void saveAndCloseCommandAndCtrlEnterRunOnSaveOnlyInEditMode() {
        var form = form();
        AtomicInteger saves = new AtomicInteger();
        form.setOnSave(saves::incrementAndGet);
        form.withDefaultCommands();

        CommandBar.forForm(form).command(CrudAction.SAVE.id()).button().click();
        assertThat(saves).hasValue(1);

        form.ctrlEnter();
        assertThat(saves).hasValue(2);

        form.setReadOnly(true);
        form.ctrlEnter();
        assertThat(saves).hasValue(2);
    }

    @Test
    void ctrlEnterWithoutDefaultCommandsDoesNothing() {
        var form = form();
        AtomicInteger saves = new AtomicInteger();
        form.setOnSave(saves::incrementAndGet);

        form.ctrlEnter();

        assertThat(saves).hasValue(0);
    }

    @Test
    void menuCommandHasNoPanelButtonButShowsPanel() {
        var form = form();
        form.addMenuCommand("service", "Служебная", () -> { });
        var command = CommandBar.forForm(form).command(ActionId.of("item.service"));

        assertThat(command.button()).isNull();
        assertThat(CommandBar.forForm(form).isVisible()).isTrue();
    }

    @Test
    void withDefaultButtonsIsDeprecatedAliasWithoutCancelAndFooterStaysEmptyAndHidden() {
        var form = form();

        @SuppressWarnings("deprecation")
        var same = form.withDefaultButtons();

        assertThat(same).isSameAs(form);
        assertThat(CommandBar.forForm(form).hasCommand(CrudAction.SAVE.id())).isTrue();
        assertThat(form.getFooter().getComponentCount()).isZero();
        assertThat(form.getFooter().isVisible()).isFalse();
    }

    @Test
    void customizerComponentInFooterMakesFooterVisible() {
        var form = form();
        Component own = new Span("своя кнопка");

        form.getFooter().add(own);
        assertThat(form.getFooter().isVisible()).isTrue();

        form.getFooter().remove(own);
        assertThat(form.getFooter().isVisible()).isFalse();
    }

    @Test
    void withDefaultCommandsIsIdempotent() {
        var form = form();

        form.withDefaultCommands();
        form.withDefaultCommands();

        assertThat(CommandBar.forForm(form).commands().stream()
            .filter(c -> c.id().equals(CrudAction.SAVE.id()))).hasSize(1);
    }
}
