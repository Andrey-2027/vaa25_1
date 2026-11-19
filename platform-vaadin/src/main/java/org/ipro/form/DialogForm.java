package org.ipro.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.form.action.ActionId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Модальная диалоговая форма платформы по образцу диалогов 1С: заголовок с кнопками
 * «развернуть» и «закрыть», командная панель, тело, строка-подсказка и футер «OK / Отмена».
 *
 * <pre>
 * ┌─ заголовок ───────────────────────────── [⛶] [✕] ┐
 * │ [Команда] [Команда] [Удалить]            [Ещё ▾]  │  командная панель (если есть команды)
 * │ тело (растягивается)                              │
 * │ строка-подсказка (если не пуста)                  │
 * │ [доп. кнопки слева]              [ OK ] [Отмена]  │
 * └───────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>Закрытие.</b> Щелчок мимо окна ничего не делает. ✕, Esc и «Отмена» идут одним
 * путём — {@link #cancel()}; OK и Enter — {@link #ok()}. Окно закрывается <em>до</em>
 * вызова {@link #onResult(Consumer)}, поэтому обработчик результата может сразу открыть
 * следующее окно. Унаследованный {@link #close()} — техническое закрытие без
 * {@code closeGuard}, {@code onResult} и {@code onCancel}.</p>
 *
 * <p><b>Содержимое.</b> Контейнерные методы {@code add/remove/replace/...} работают с
 * {@linkplain #getBody() телом}, поэтому потребитель, вызывающий {@code add(грид)},
 * раскладку зон не ломает. Шапка и футер — виртуальные дети диалога: {@code getChildren()}
 * их не видит; от диалога обход видит служебный корень (панель, тело, подсказка), а команды
 * панели тесты берут через {@code CommandBar.forForm(dialog)} — как у {@code ListForm}.</p>
 *
 * <p>Собственных стилей и файлов темы класс не добавляет: окно выглядит как остальные окна
 * приложения (глобальные стили). Inline заданы только переменные Lumo.</p>
 *
 * @param <R> тип результата (выбранная запись, готовая модель, {@link Void}…)
 */
public class DialogForm<R> extends Dialog {

    /** Типовые размеры окна. */
    public enum Size {
        /** 480 px, высота по содержимому. */
        SMALL("480px", null),
        /** 720 × 520 px (по умолчанию). */
        MEDIUM("720px", "520px"),
        /** 960 × 640 px. */
        LARGE("960px", "640px");

        private final String width;
        private final String height;

        Size(String width, String height) {
            this.width = width;
            this.height = height;
        }
    }

    /** Разрешение закрыть окно: вызвать {@code proceed}, если закрывать можно. */
    @FunctionalInterface
    public interface CloseGuard {
        void beforeClose(Runnable proceed);
    }

    private static final String ICON_MAXIMIZE = "Развернуть";
    private static final String ICON_RESTORE = "Восстановить";

    private final VerticalLayout root = new VerticalLayout();
    private final CommandPanel commands = new CommandPanel();
    private final VerticalLayout body = new VerticalLayout();
    private final Span hint = new Span();
    private final FooterGroup footerStart = new FooterGroup();
    private final Button okButton = new Button("OK");
    private final Button cancelButton = new Button("Отмена");
    private final Button maximizeButton = new Button(new Icon(VaadinIcon.EXPAND_SQUARE));
    private final Button closeButton = new Button(new Icon(VaadinIcon.CLOSE_SMALL));

    private boolean bodyReady;
    private boolean footerless;

    private Supplier<R> okHandler;
    private Consumer<R> resultListener;
    private Runnable cancelListener;
    private CloseGuard closeGuard;
    private boolean enterIsOk;
    private Registration enterShortcut;
    private Registration gridEnterShortcut;
    private Registration ctrlEnterShortcut;

    private boolean maximized;
    private Frame savedFrame;

    /** Размер и положение окна до разворачивания. */
    private record Frame(String width, String height, String top, String left,
                         String maxWidth, String maxHeight, boolean draggable, boolean resizable) {
    }

    public DialogForm(String title) {
        setHeaderTitle(title);
        setModal(true);
        setCloseOnOutsideClick(false);
        setCloseOnEsc(true);
        setDraggable(true);
        setResizable(true);
        setKeepInViewport(true);
        setMinWidth("320px");
        setMaxWidth("95vw");
        setMaxHeight("95vh");

        buildBody();
        buildHeader();
        buildFooter();
        size(Size.MEDIUM);

        // Пока слушатель задан, Vaadin сам окно не закрывает: Esc идёт тем же путём, что «Отмена».
        addDialogCloseActionListener(event -> cancel());
        setEnterIsOk(true);
        addAttachListener(event -> getElement().executeJs("""
            const overlay = this.$.overlay;
            if (overlay.__iproCommitBeforeEscape) return;
            overlay.__iproCommitBeforeEscape = true;
            overlay.addEventListener('vaadin-overlay-close', event => {
                const key = event.detail.sourceEvent;
                if (!event.defaultPrevented && key?.key === 'Escape') {
                    (%s)(key);
                }
            });
            this.addEventListener('keydown', event => {
                const cell = event.composedPath()[0];
                if (this.hasAttribute('data-grid-enter-ok') && event.key === 'Enter'
                    && !event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey
                    && !event.defaultPrevented && !event.isComposing && !event.repeat
                    && cell?.matches?.('td[part~="body-cell"]')) {
                    // The grid reserves Enter for entering its interactive cells.
                    // Only the cell itself confirms here. Its buttons still handle their own keys.
                    event.preventDefault();
                    event.stopPropagation();
                    this.dispatchEvent(new Event('ipro-grid-confirm'));
                }
            }, {capture: true});
            """.formatted(FormKeyboard.COMMIT_INPUT)));
    }

    // ------------------------------------------------------------------ сборка

    private void buildBody() {
        root.setSizeFull();
        root.setPadding(false);
        root.setSpacing(true);
        root.getThemeList().add("spacing-s");

        ComponentUtil.setData(this, CommandBar.class, commands.bar());

        body.setPadding(false);
        body.setSpacing(false);
        body.setWidthFull();
        body.getStyle().set("min-height", "0");

        hint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        hint.getStyle().set("font-size", "var(--lumo-font-size-s)");
        hint.setVisible(false);

        root.add(commands.bar(), body, hint);
        root.setFlexGrow(1, body);

        super.add(root);
        bodyReady = true;
    }

    private void buildHeader() {
        configureHeaderButton(maximizeButton, ICON_MAXIMIZE);
        maximizeButton.addClickListener(event -> toggleMaximized());
        configureHeaderButton(closeButton, "Закрыть");
        closeButton.addClickListener(event -> cancel());
        getHeader().add(maximizeButton, closeButton);

        // Двойной щелчок по тексту заголовка: событие всплывает от <h2 slot="title"> до диалога.
        getElement().addEventListener("dblclick", event -> {
                if (maximizeButton.isVisible()) {
                    toggleMaximized();
                }
            })
            .setFilter("event.target.getAttribute && event.target.getAttribute('slot') === 'title'");
    }

    private static void configureHeaderButton(Button button, String caption) {
        button.addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_TERTIARY_INLINE);
        button.setTooltipText(caption);
        button.getElement().setAttribute("aria-label", caption);
    }

    private void buildFooter() {
        okButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        okButton.addClickListener(event -> {
            if (okButton.isEnabled()) {
                ok();
            }
        });
        // OK появляется только после withOk(...); до этого окно закрывается единственной кнопкой.
        okButton.setVisible(false);
        cancelButton.addClickListener(event -> cancel());
        getFooter().add(footerStart, okButton, cancelButton);
    }

    // ------------------------------------------------------------------ тело

    /** Тело формы: сюда попадает всё, что добавлено через {@code add(...)}. */
    public VerticalLayout getBody() {
        return body;
    }

    /** Строка-подсказка под телом; {@code null} или пустая строка скрывают её. */
    public void setHint(String text) {
        boolean empty = text == null || text.isBlank();
        hint.setText(empty ? "" : text);
        hint.setVisible(!empty);
    }

    @Override
    public void add(Component... components) {
        if (bodyReady) {
            body.add(components);
        } else {
            super.add(components);
        }
    }

    @Override
    public void add(Collection<Component> components) {
        if (bodyReady) {
            body.add(components);
        } else {
            super.add(components);
        }
    }

    @Override
    public void add(String text) {
        if (bodyReady) {
            body.add(text);
        } else {
            super.add(text);
        }
    }

    @Override
    public void remove(Component... components) {
        if (bodyReady) {
            body.remove(components);
        } else {
            super.remove(components);
        }
    }

    @Override
    public void remove(Collection<Component> components) {
        if (bodyReady) {
            body.remove(components);
        } else {
            super.remove(components);
        }
    }

    @Override
    public void removeAll() {
        if (bodyReady) {
            body.removeAll();
        } else {
            super.removeAll();
        }
    }

    @Override
    public void addComponentAtIndex(int index, Component component) {
        if (bodyReady) {
            body.addComponentAtIndex(index, component);
        } else {
            super.addComponentAtIndex(index, component);
        }
    }

    @Override
    public void addComponentAsFirst(Component component) {
        if (bodyReady) {
            body.addComponentAsFirst(component);
        } else {
            super.addComponentAsFirst(component);
        }
    }

    @Override
    public void replace(Component oldComponent, Component newComponent) {
        if (bodyReady) {
            body.replace(oldComponent, newComponent);
        } else {
            super.replace(oldComponent, newComponent);
        }
    }

    @Override
    public int indexOf(Component component) {
        return bodyReady ? body.indexOf(component) : super.indexOf(component);
    }

    @Override
    public int getComponentCount() {
        return bodyReady ? body.getComponentCount() : super.getComponentCount();
    }

    @Override
    public Component getComponentAt(int index) {
        return bodyReady ? body.getComponentAt(index) : super.getComponentAt(index);
    }

    // ------------------------------------------------------------------ командная панель

    /** Обычная команда панели; ключ — часть {@link ActionId} вида {@code dialog.<key>}. */
    public FormCommand addCommand(String key, String title, VaadinIcon icon, Runnable handler) {
        return commands.add(commandId(key), title, icon, handler);
    }

    /** Разрушительная команда панели (красная). */
    public FormCommand addDangerCommand(String key, String title, VaadinIcon icon, Runnable handler) {
        return commands.addDanger(commandId(key), title, icon, handler);
    }

    /** Команда только для меню «Ещё»; кнопки на панели у неё нет. */
    public FormCommand addMenuCommand(String key, String title, Runnable handler) {
        return commands.addMenu(commandId(key), title, handler);
    }

    private static ActionId commandId(String key) {
        Objects.requireNonNull(key, "key");
        return ActionId.of("dialog." + key);
    }

    /** Внутренний рендерер команд; для {@code SelectionForm} (наполнение «Ещё») и тестов. */
    CommandBar commandBar() {
        return commands.bar();
    }

    // ------------------------------------------------------------------ футер

    /** Задать OK: обработчик возвращает результат; {@code null} — окно остаётся открытым. */
    public DialogForm<R> withOk(String caption, Supplier<R> handler) {
        okButton.setText(Objects.requireNonNull(caption, "caption"));
        okHandler = Objects.requireNonNull(handler, "handler");
        okButton.setVisible(!footerless);
        updateConfirmationShortcuts();
        return this;
    }

    /** Подпись кнопки отмены (по умолчанию «Отмена»). */
    public DialogForm<R> withCancel(String caption) {
        cancelButton.setText(Objects.requireNonNull(caption, "caption"));
        return this;
    }

    /** Окно без результата: одна кнопка «Закрыть». */
    public DialogForm<R> withoutOk() {
        okHandler = null;
        okButton.setVisible(false);
        cancelButton.setText("Закрыть");
        updateConfirmationShortcuts();
        return this;
    }

    /**
     * Окно без подвала (карточка элемента: команды живут на панели, а не внизу). ✕ и Esc по-прежнему
     * ведут в {@link #cancel()}; Ctrl+Enter работает, если задан OK ({@link #withOk}).
     */
    public DialogForm<R> withoutFooter() {
        footerless = true;
        getFooter().removeAll();
        okButton.setVisible(false);
        updateConfirmationShortcuts();
        return this;
    }

    /** Ctrl+Enter: то же, что нажатие доступной кнопки OK. */
    void ctrlEnter() {
        if (okHandler != null && okButton.isEnabled()) {
            ok();
        }
    }

    public Button getOkButton() {
        return okButton;
    }

    public Button getCancelButton() {
        return cancelButton;
    }

    /** Левая группа футера для дополнительных кнопок; пустая — скрыта. */
    public HorizontalLayout getFooterStart() {
        return footerStart;
    }

    public void setOkEnabled(boolean enabled) {
        okButton.setEnabled(enabled);
        updateConfirmationShortcuts();
    }

    /** Enter = OK (по умолчанию включено). Окнам с многострочными полями ярлык отключают. */
    public void setEnterIsOk(boolean enabled) {
        enterIsOk = enabled;
        updateConfirmationShortcuts();
    }

    private void updateConfirmationShortcuts() {
        boolean canConfirm = okHandler != null && okButton.isEnabled();
        boolean plainEnter = canConfirm && enterIsOk && !footerless;
        if (plainEnter && enterShortcut == null) {
            enterShortcut = FormKeyboard.confirm(this, false, this::ctrlEnter);
            gridEnterShortcut = getElement().addEventListener("ipro-grid-confirm", event -> ctrlEnter());
            getElement().setAttribute("data-grid-enter-ok", "");
        } else if (!plainEnter && enterShortcut != null) {
            enterShortcut.remove();
            enterShortcut = null;
            gridEnterShortcut.remove();
            gridEnterShortcut = null;
            getElement().removeAttribute("data-grid-enter-ok");
        }
        if (canConfirm && ctrlEnterShortcut == null) {
            ctrlEnterShortcut = FormKeyboard.confirm(this, true, this::ctrlEnter);
        } else if (!canConfirm && ctrlEnterShortcut != null) {
            ctrlEnterShortcut.remove();
            ctrlEnterShortcut = null;
        }
    }

    // ------------------------------------------------------------------ результат

    /** Получатель результата; вызывается после закрытия окна. */
    public DialogForm<R> onResult(Consumer<R> listener) {
        this.resultListener = listener;
        return this;
    }

    /** Вызывается после закрытия окна через ✕, Esc или «Отмена». */
    public DialogForm<R> onCancel(Runnable listener) {
        this.cancelListener = listener;
        return this;
    }

    /** Условие закрытия (например, «Данные изменены. Сохранить?»). */
    public DialogForm<R> closeGuard(CloseGuard guard) {
        this.closeGuard = guard;
        return this;
    }

    /** Нажатие OK: обработчик вернул значение — закрыть и передать; вернул {@code null} — остаться. */
    public void ok() {
        if (okHandler == null) {
            return;
        }
        R result = okHandler.get();
        if (result != null) {
            closeWith(result);
        }
    }

    /** Закрыть окно с готовым результатом (двойной щелчок по строке и т. п.). */
    public void closeWith(R result) {
        super.close();
        if (resultListener != null) {
            resultListener.accept(result);
        }
    }

    /** Отмена: ✕, Esc и «Отмена» приходят сюда. */
    public void cancel() {
        if (closeGuard != null) {
            closeGuard.beforeClose(this::finishCancel);
        } else {
            finishCancel();
        }
    }

    private void finishCancel() {
        super.close();
        if (cancelListener != null) {
            cancelListener.run();
        }
    }

    // ------------------------------------------------------------------ окно

    /** Типовой размер окна. */
    public DialogForm<R> size(Size size) {
        Objects.requireNonNull(size, "size");
        setWidth(size.width);
        setHeight(size.height);
        return this;
    }

    @Override
    public void setResizable(boolean resizable) {
        super.setResizable(resizable);
        // ⛶ есть только у окон с изменяемым размером; поле ещё не создано во время super().
        if (maximizeButton != null) {
            maximizeButton.setVisible(resizable);
        }
    }

    public boolean isMaximized() {
        return maximized;
    }

    public void setMaximized(boolean maximize) {
        if (maximize == maximized) {
            return;
        }
        if (maximize) {
            savedFrame = new Frame(getWidth(), getHeight(), getTop(), getLeft(),
                getMaxWidth(), getMaxHeight(), isDraggable(), isResizable());
            super.setDraggable(false);
            super.setResizable(false);
            setMaxWidth(null);
            setMaxHeight(null);
            setTop("0");
            setLeft("0");
            setWidth("100vw");
            setHeight("100vh");
        } else if (savedFrame != null) {
            Frame frame = savedFrame;
            savedFrame = null;
            setMaxWidth(frame.maxWidth());
            setMaxHeight(frame.maxHeight());
            setWidth(frame.width());
            setHeight(frame.height());
            setTop(frame.top());
            setLeft(frame.left());
            super.setDraggable(frame.draggable());
            super.setResizable(frame.resizable());
        }
        maximized = maximize;
        maximizeButton.setIcon(new Icon(maximize ? VaadinIcon.COMPRESS_SQUARE : VaadinIcon.EXPAND_SQUARE));
        String caption = maximize ? ICON_RESTORE : ICON_MAXIMIZE;
        maximizeButton.setTooltipText(caption);
        maximizeButton.getElement().setAttribute("aria-label", caption);
    }

    private void toggleMaximized() {
        setMaximized(!maximized);
    }

    /** Левая группа футера: скрыта, пока в ней нет кнопок; прижимает остальные вправо. */
    private static final class FooterGroup extends HorizontalLayout {

        private FooterGroup() {
            setPadding(false);
            setAlignItems(FlexComponent.Alignment.CENTER);
            getStyle().set("margin-inline-end", "auto");
            setVisible(false);
        }

        private void sync() {
            setVisible(getComponentCount() > 0);
        }

        @Override
        public void add(Component... components) {
            super.add(components);
            sync();
        }

        @Override
        public void add(Collection<Component> components) {
            super.add(components);
            sync();
        }

        @Override
        public void addComponentAsFirst(Component component) {
            super.addComponentAsFirst(component);
            sync();
        }

        @Override
        public void addComponentAtIndex(int index, Component component) {
            super.addComponentAtIndex(index, component);
            sync();
        }

        @Override
        public void remove(Component... components) {
            super.remove(components);
            sync();
        }

        @Override
        public void remove(Collection<Component> components) {
            super.remove(components);
            sync();
        }

        @Override
        public void removeAll() {
            super.removeAll();
            sync();
        }
    }
}
