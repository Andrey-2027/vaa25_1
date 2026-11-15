package org.ipro.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.dom.Element;
import org.ipro.form.action.ActionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DialogFormTest {

    private UI ui;

    @BeforeEach
    void attachUi() {
        // UI.getCurrent() хранится слабой ссылкой: без поля UI собирается GC посреди теста.
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

    private static List<Component> components(Element container) {
        List<Component> result = new ArrayList<>();
        container.getChildren().forEach(child -> child.getComponent().ifPresent(result::add));
        return result;
    }

    private static List<Component> headerButtons(DialogForm<?> dialog) {
        return components(dialog.getHeader().getElement());
    }

    private static Button headerButton(DialogForm<?> dialog, String label) {
        return headerButtons(dialog).stream().map(Button.class::cast)
            .filter(button -> label.equals(button.getElement().getAttribute("aria-label")))
            .findFirst().orElseThrow();
    }

    private static List<Component> footerComponents(DialogForm<?> dialog) {
        return components(dialog.getFooter().getElement());
    }

    // 1 ------------------------------------------------------------------------------------

    @Test
    void newWindowIsModalDraggableAndIgnoresOutsideClickWithoutForm1cTheme() {
        var dialog = new DialogForm<String>("Заголовок");

        assertThat(dialog.isCloseOnOutsideClick()).isFalse();
        assertThat(dialog.isCloseOnEsc()).isTrue();
        assertThat(dialog.isModal()).isTrue();
        assertThat(dialog.isDraggable()).isTrue();
        assertThat(dialog.isResizable()).isTrue();
        assertThat(dialog.isKeepInViewport()).isTrue();
        assertThat(dialog.getHeaderTitle()).isEqualTo("Заголовок");
        assertThat(dialog.getElement().getThemeList()).doesNotContain("form-1c");
        assertThat(dialog.getWidth()).isEqualTo("720px");
        assertThat(dialog.getHeight()).isEqualTo("520px");
        assertThat(dialog.getMinWidth()).isEqualTo("320px");
        assertThat(dialog.getMaxWidth()).isEqualTo("95vw");
        assertThat(dialog.getMaxHeight()).isEqualTo("95vh");
    }

    @Test
    void sizePresetsSetWidthAndHeight() {
        var dialog = new DialogForm<String>("Окно");

        dialog.size(DialogForm.Size.SMALL);
        assertThat(dialog.getWidth()).isEqualTo("480px");
        assertThat(dialog.getHeight()).isNull();

        dialog.size(DialogForm.Size.LARGE);
        assertThat(dialog.getWidth()).isEqualTo("960px");
        assertThat(dialog.getHeight()).isEqualTo("640px");
    }

    // 2 ------------------------------------------------------------------------------------

    @Test
    void addGoesToBodyAndRemoveAllKeepsCommandBarAndHint() {
        var dialog = new DialogForm<String>("Окно");
        dialog.addCommand("create", "Создать", VaadinIcon.PLUS, () -> { });
        dialog.setHint("Подсказка");
        Div first = new Div();
        Div second = new Div();

        dialog.add(first, second);

        assertThat(dialog.getBody().getChildren().toList()).containsExactly(first, second);
        assertThat(dialog.getComponentCount()).isEqualTo(2);
        assertThat(dialog.indexOf(second)).isEqualTo(1);
        assertThat(dialog.getComponentAt(0)).isSameAs(first);

        Div inserted = new Div();
        dialog.addComponentAtIndex(1, inserted);
        dialog.addComponentAsFirst(new Div());
        assertThat(dialog.getComponentCount()).isEqualTo(4);
        assertThat(dialog.indexOf(inserted)).isEqualTo(2);

        dialog.removeAll();

        assertThat(dialog.getComponentCount()).isZero();
        var rootChildren = dialog.getBody().getParent().orElseThrow().getChildren().toList();
        assertThat(rootChildren).contains(dialog.commandBar());
        assertThat(rootChildren).hasSize(3);
    }

    // 3, 4 ---------------------------------------------------------------------------------

    @Test
    void escapeCancelsWithoutResult() {
        var dialog = new DialogForm<String>("Окно").withOk("OK", () -> "x");
        var cancelled = new AtomicInteger();
        var results = new AtomicInteger();
        dialog.onCancel(cancelled::incrementAndGet).onResult(value -> results.incrementAndGet());
        dialog.open();
        assertThat(dialog.isOpened()).isTrue();

        ComponentUtil.fireEvent(dialog, new Dialog.DialogCloseActionEvent(dialog, true));

        assertThat(dialog.isOpened()).isFalse();
        assertThat(cancelled).hasValue(1);
        assertThat(results).hasValue(0);
    }

    @Test
    void closeButtonInHeaderCancelsLikeEscape() {
        var dialog = new DialogForm<String>("Окно").withOk("OK", () -> "x");
        var cancelled = new AtomicInteger();
        var results = new AtomicInteger();
        dialog.onCancel(cancelled::incrementAndGet).onResult(value -> results.incrementAndGet());
        dialog.open();

        headerButton(dialog, "Закрыть").click();

        assertThat(dialog.isOpened()).isFalse();
        assertThat(cancelled).hasValue(1);
        assertThat(results).hasValue(0);
    }

    @Test
    void cancelButtonCancels() {
        var dialog = new DialogForm<String>("Окно").withOk("OK", () -> "x");
        var cancelled = new AtomicInteger();
        dialog.onCancel(cancelled::incrementAndGet);
        dialog.open();

        dialog.getCancelButton().click();

        assertThat(dialog.isOpened()).isFalse();
        assertThat(cancelled).hasValue(1);
    }

    // 5, 6 ---------------------------------------------------------------------------------

    @Test
    void okWithNullKeepsWindowOpenAndOkWithValueClosesBeforeResult() {
        var value = new AtomicReference<String>();
        var openedWhenDelivered = new AtomicReference<Boolean>();
        var received = new ArrayList<String>();
        var dialog = new DialogForm<String>("Окно");
        dialog.withOk("OK", value::get).onResult(result -> {
            openedWhenDelivered.set(dialog.isOpened());
            received.add(result);
        });
        dialog.open();

        dialog.ok();
        assertThat(dialog.isOpened()).isTrue();
        assertThat(received).isEmpty();

        value.set("выбрано");
        dialog.getOkButton().click();

        assertThat(dialog.isOpened()).isFalse();
        assertThat(received).containsExactly("выбрано");
        assertThat(openedWhenDelivered.get()).isFalse();
    }

    @Test
    void closeWithBehavesLikeOkAndPlainCloseIsSilent() {
        var received = new ArrayList<String>();
        var cancelled = new AtomicInteger();
        var dialog = new DialogForm<String>("Окно").withOk("OK", () -> null);
        dialog.onResult(received::add).onCancel(cancelled::incrementAndGet);

        dialog.open();
        dialog.closeWith("готово");
        assertThat(dialog.isOpened()).isFalse();
        assertThat(received).containsExactly("готово");

        dialog.open();
        dialog.close();
        assertThat(dialog.isOpened()).isFalse();
        assertThat(received).hasSize(1);
        assertThat(cancelled).hasValue(0);
    }

    @Test
    void okButtonIgnoresClickWhenDisabled() {
        var dialog = new DialogForm<String>("Окно").withOk("OK", () -> "x");
        var received = new ArrayList<String>();
        dialog.onResult(received::add);
        dialog.open();
        dialog.setOkEnabled(false);

        dialog.getOkButton().click();

        assertThat(received).isEmpty();
        assertThat(dialog.isOpened()).isTrue();
    }

    // 7 ------------------------------------------------------------------------------------

    @Test
    void closeGuardWithoutProceedKeepsWindowOpen() {
        var dialog = new DialogForm<String>("Окно");
        var cancelled = new AtomicInteger();
        var pending = new AtomicReference<Runnable>();
        dialog.onCancel(cancelled::incrementAndGet).closeGuard(pending::set);
        dialog.open();

        dialog.cancel();
        assertThat(dialog.isOpened()).isTrue();
        assertThat(cancelled).hasValue(0);

        pending.get().run();
        assertThat(dialog.isOpened()).isFalse();
        assertThat(cancelled).hasValue(1);
    }

    @Test
    void closeGuardProceedingImmediatelyClosesWindow() {
        var dialog = new DialogForm<String>("Окно");
        dialog.closeGuard(Runnable::run);
        dialog.open();

        headerButton(dialog, "Закрыть").click();

        assertThat(dialog.isOpened()).isFalse();
    }

    // 8 ------------------------------------------------------------------------------------

    @Test
    void maximizeAndRestoreReturnExactSizeAndPosition() {
        var dialog = new DialogForm<String>("Окно");
        dialog.setWidth("500px");
        dialog.setHeight("300px");
        dialog.setTop("40px");
        dialog.setLeft("60px");
        dialog.open();

        dialog.setMaximized(true);

        assertThat(dialog.isMaximized()).isTrue();
        assertThat(dialog.getWidth()).isEqualTo("100vw");
        assertThat(dialog.getHeight()).isEqualTo("100vh");
        assertThat(dialog.getTop()).isEqualTo("0");
        assertThat(dialog.getLeft()).isEqualTo("0");
        assertThat(dialog.getMaxWidth()).isNull();
        assertThat(dialog.getMaxHeight()).isNull();
        assertThat(dialog.isDraggable()).isFalse();
        assertThat(dialog.isResizable()).isFalse();
        assertThat(headerButton(dialog, "Восстановить").isVisible()).isTrue();

        dialog.setMaximized(false);

        assertThat(dialog.isMaximized()).isFalse();
        assertThat(dialog.getWidth()).isEqualTo("500px");
        assertThat(dialog.getHeight()).isEqualTo("300px");
        assertThat(dialog.getTop()).isEqualTo("40px");
        assertThat(dialog.getLeft()).isEqualTo("60px");
        assertThat(dialog.getMaxWidth()).isEqualTo("95vw");
        assertThat(dialog.getMaxHeight()).isEqualTo("95vh");
        assertThat(dialog.isDraggable()).isTrue();
        assertThat(dialog.isResizable()).isTrue();
        assertThat(headerButton(dialog, "Развернуть").isVisible()).isTrue();
    }

    @Test
    void maximizeButtonTogglesAndIsHiddenForNonResizableWindow() {
        var dialog = new DialogForm<String>("Окно");
        dialog.open();

        headerButton(dialog, "Развернуть").click();
        assertThat(dialog.isMaximized()).isTrue();
        headerButton(dialog, "Восстановить").click();
        assertThat(dialog.isMaximized()).isFalse();

        dialog.setResizable(false);
        assertThat(headerButton(dialog, "Развернуть").isVisible()).isFalse();
        assertThat(headerButton(dialog, "Закрыть").isVisible()).isTrue();
    }

    // 9 ------------------------------------------------------------------------------------

    @Test
    void commandBarIsHiddenUntilCommandIsRegistered() {
        var dialog = new DialogForm<String>("Окно");
        assertThat(dialog.commandBar().isVisible()).isFalse();

        dialog.addCommand("create", "Создать", VaadinIcon.PLUS, () -> { });

        assertThat(dialog.commandBar().isVisible()).isTrue();
        var command = dialog.commandBar().command(ActionId.of("dialog.create"));
        assertThat(command.isOnPanel()).isTrue();
        assertThat(command.button().getText()).isEqualTo("Создать");
    }

    @Test
    void iconOnlyCommandHasNoTextButKeepsTooltipAndLabel() {
        var dialog = new DialogForm<String>("Окно");
        var clicks = new AtomicInteger();
        dialog.addDangerCommand("delete", "Удалить", VaadinIcon.TRASH, clicks::incrementAndGet).iconOnly();

        var button = dialog.commandBar().command(ActionId.of("dialog.delete")).button();

        assertThat(button.getText()).isEmpty();
        assertThat(button.getElement().getAttribute("aria-label")).isEqualTo("Удалить");
        assertThat(button.getTooltip().getText()).isEqualTo("Удалить");
        button.click();
        assertThat(clicks).hasValue(1);
    }

    @Test
    void panelKeepsRegistrationOrderAndMenuCommandHasNoButton() {
        var dialog = new DialogForm<String>("Окно");
        dialog.addCommand("zeta", "Зета", VaadinIcon.PLUS, () -> { });
        dialog.addCommand("alpha", "Альфа", VaadinIcon.EDIT, () -> { });
        dialog.addMenuCommand("standard-view", "Стандартный вид", () -> { });

        var row = dialog.commandBar().getChildren().findFirst().orElseThrow();
        assertThat(row.getChildren().map(child -> ((Button) child).getElement().getAttribute("aria-label")))
            .containsExactly("Зета", "Альфа");
        var registered = dialog.commandBar().command(ActionId.of("dialog.standard-view"));
        assertThat(registered.button()).isNull();
        assertThat(registered.isOnPanel()).isFalse();
        assertThat(dialog.commandBar().isVisible()).isTrue();
    }

    @Test
    void commandBarIsReachableThroughForFormLikeInListForm() {
        var dialog = new DialogForm<String>("Окно");
        dialog.addCommand("create", "Создать", VaadinIcon.PLUS, () -> { });

        assertThat(CommandBar.forForm(dialog)).isSameAs(dialog.commandBar());
        assertThat(CommandBar.forForm(dialog).hasCommand(ActionId.of("dialog.create"))).isTrue();
    }

    @Test
    void commandStateIsSharedByButtonAndMenuItem() {
        var dialog = new DialogForm<String>("Окно");
        var command = dialog.addCommand("copy", "Копировать", VaadinIcon.COPY, () -> { });
        var registered = dialog.commandBar().command(ActionId.of("dialog.copy"));

        command.setEnabled(false);
        assertThat(registered.button().isEnabled()).isFalse();
        assertThat(registered.menuItem().isEnabled()).isFalse();

        command.setEnabled(true).setVisible(false);
        assertThat(registered.button().isVisible()).isFalse();
        assertThat(registered.menuItem().isVisible()).isFalse();
    }

    // 10 -----------------------------------------------------------------------------------

    @Test
    void withoutOkLeavesSingleCloseButtonAndEmptyStartGroupIsHidden() {
        var dialog = new DialogForm<String>("Окно").withoutOk();

        assertThat(dialog.getOkButton().isVisible()).isFalse();
        assertThat(dialog.getCancelButton().isVisible()).isTrue();
        assertThat(dialog.getCancelButton().getText()).isEqualTo("Закрыть");
        assertThat(dialog.getFooterStart().isVisible()).isFalse();
        assertThat(footerComponents(dialog).stream().filter(Component::isVisible)
            .filter(Button.class::isInstance)).containsExactly(dialog.getCancelButton());
    }

    @Test
    void footerStartIsShownWhileItHoldsButtons() {
        var dialog = new DialogForm<String>("Окно").withOk("Выбрать", () -> "x");
        Button extra = new Button("Ещё одна");

        dialog.getFooterStart().add(extra);
        assertThat(dialog.getFooterStart().isVisible()).isTrue();
        assertThat(dialog.getFooterStart().getStyle().get("margin-inline-end")).isEqualTo("auto");

        dialog.getFooterStart().remove(extra);
        assertThat(dialog.getFooterStart().isVisible()).isFalse();

        assertThat(dialog.getOkButton().getText()).isEqualTo("Выбрать");
        assertThat(dialog.getOkButton().isVisible()).isTrue();
        assertThat(footerComponents(dialog)).containsSubsequence(dialog.getOkButton(), dialog.getCancelButton());
    }

    @Test
    void hintIsHiddenWhenEmpty() {
        var dialog = new DialogForm<String>("Окно");
        var hint = dialog.getBody().getParent().orElseThrow().getChildren()
            .filter(com.vaadin.flow.component.html.Span.class::isInstance).findFirst().orElseThrow();

        assertThat(hint.isVisible()).isFalse();
        dialog.setHint("Описание отчёта");
        assertThat(hint.isVisible()).isTrue();
        assertThat(hint.getElement().getText()).isEqualTo("Описание отчёта");
        dialog.setHint("  ");
        assertThat(hint.isVisible()).isFalse();
    }

    @Test
    void enterShortcutCanBeSwitchedOff() {
        var dialog = new DialogForm<String>("Окно").withOk("OK", () -> "x");

        // Проверяем, что переключение не падает и повторяется без утечки ярлыков.
        dialog.setEnterIsOk(false);
        dialog.setEnterIsOk(false);
        dialog.setEnterIsOk(true);
        dialog.setEnterIsOk(true);

        assertThat(dialog.getOkButton().isEnabled()).isTrue();
    }
}
