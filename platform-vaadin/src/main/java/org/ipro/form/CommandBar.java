package org.ipro.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.contextmenu.SubMenu;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.ipro.form.action.ActionId;
import org.ipro.form.builder.ListCommandLayout;
import org.ipro.form.builder.ListUiCommand;
import org.ipro.form.action.CrudAction;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Внутренний рендерер команд списка.
 *
 * <p>Одна команда имеет одно состояние и два представления: кнопку на панели (если она туда
 * помещена) и пункт меню «Ещё». Состояние меняется только здесь; стандартные Vaadin-сеттеры
 * кнопки не являются вторым источником конфигурации.</p>
 *
 * <p>Класс намеренно не является публичным входом платформы: форма возвращает наружу
 * совместимую область {@code getToolbar()}, а не этот рендерер.</p>
 */
@com.vaadin.flow.component.dependency.StyleSheet("context://VAADIN/static/ipro/command-bar.css")
public final class CommandBar extends HorizontalLayout {

    /** Группа пункта в «Ещё». Настройки и сервис живут в подменю, рабочие команды — сразу. */
    public enum Group {
        WORK,
        SETTINGS,
        SERVICE
    }

    private final Div commandRow = new Div();
    private final MenuBar moreBar = new MenuBar();
    private final MenuItem moreItem;
    private final SubMenu workMenu;
    private final SubMenu settingsMenu;
    private final SubMenu serviceMenu;
    private final MenuItem settingsItem;
    private final MenuItem serviceItem;

    private final Map<ActionId, Command> commands = new LinkedHashMap<>();
    private ListCommandLayout layout = ListCommandLayout.platformDefaults();

    /** Внутренний доступ координатора и адаптеров без расширения API формы. */
    public static CommandBar forForm(Component form) {
        return Objects.requireNonNull(com.vaadin.flow.component.ComponentUtil.getData(form, CommandBar.class),
            "CommandBar is not installed on this form");
    }

    public CommandBar() {
        addClassName("ipro-command-bar");
        setWidthFull();
        setPadding(false);
        setSpacing(false);
        setAlignItems(Alignment.CENTER);
        getStyle().set("gap", "var(--lumo-space-s)");
        getStyle().set("flex-wrap", "nowrap");

        commandRow.getStyle().set("display", "flex");
        commandRow.getStyle().set("align-items", "center");
        commandRow.getStyle().set("gap", "var(--lumo-space-s)");
        commandRow.getStyle().set("flex", "1 1 auto");
        commandRow.getStyle().set("min-width", "0");
        commandRow.getStyle().set("overflow", "hidden");
        commandRow.getElement().setAttribute("data-cb-row", "");

        moreBar.addThemeVariants(MenuBarVariant.LUMO_TERTIARY_INLINE,
            MenuBarVariant.LUMO_SMALL, MenuBarVariant.LUMO_DROPDOWN_INDICATORS);
        moreBar.getElement().getThemeList().add("ipro-list-commands");
        moreBar.getStyle().set("flex-shrink", "0");
        moreBar.getElement().setAttribute("data-cb-more", "");

        moreItem = moreBar.addItem("Ещё");
        moreItem.getElement().setAttribute("aria-label", "Дополнительные команды");
        workMenu = moreItem.getSubMenu();

        settingsItem = workMenu.addItem("Настройки");
        settingsMenu = settingsItem.getSubMenu();
        serviceItem = workMenu.addItem("Сервис");
        serviceMenu = serviceItem.getSubMenu();
        settingsItem.setVisible(false);
        serviceItem.setVisible(false);

        add(commandRow, moreBar);
        addAttachListener(event -> {
            installOverflowMeasurement();
            scheduleGroupRefresh();
        });
        addDetachListener(event -> getElement().executeJs(
            "const bar = this; if (bar.__cbDispose) bar.__cbDispose();"));
    }

    /** Зарегистрировать рабочую команду с кнопкой и пунктом меню. */
    public Command register(ActionId id, String title, VaadinIcon icon, Runnable handler) {
        return register(id, title, icon, null, handler, Group.WORK);
    }

    /** Зарегистрировать акцентную рабочую команду. */
    public Command registerPrimary(ActionId id, String title, VaadinIcon icon, Runnable handler) {
        return register(id, title, icon, ButtonVariant.LUMO_PRIMARY, handler, Group.WORK);
    }

    /** Зарегистрировать разрушительную рабочую команду. */
    public Command registerDanger(ActionId id, String title, VaadinIcon icon, Runnable handler) {
        return register(id, title, icon, ButtonVariant.LUMO_ERROR, handler, Group.WORK);
    }

    private Command register(ActionId id, String title, VaadinIcon icon, ButtonVariant variant,
                             Runnable handler, Group group) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(handler, "handler");
        requireNew(id);

        Button button = new Button(title);
        if (icon != null) {
            button.setIcon(new Icon(icon));
        }
        if (variant != null) {
            button.addThemeVariants(variant);
        }
        button.getStyle().set("flex-shrink", "0");
        button.getStyle().set("white-space", "nowrap");
        button.getElement().setAttribute("data-cb-command", "");
        button.addClickListener(event -> execute(id));

        Command command = new Command(id, title, icon, handler, group, button, false);
        commands.put(id, command);
        command.stateChanged = this::updateGroupVisibility;
        command.orderChanged = this::applyLayout;
        createMenuItem(command);
        applyLayout();
        return command;
    }

    /** Команда только для меню; кнопки панели у неё нет. */
    public Command addMenuCommand(ActionId id, String title, Runnable handler) {
        return addMenuCommand(id, title, handler, Group.WORK);
    }

    /** Команда только для меню в заданной группе. */
    public Command addMenuCommand(ActionId id, String title, Runnable handler, Group group) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(handler, "handler");
        requireNew(id);
        Command command = new Command(id, title, null, handler, group, null, false);
        commands.put(id, command);
        command.stateChanged = this::updateGroupVisibility;
        command.orderChanged = this::applyLayout;
        createMenuItem(command);
        applyLayout();
        return command;
    }

    /** Включаемый пункт настроек; checked читается из актуального состояния грида. */
    public Command addCheckItem(ActionId id, String title, BooleanSupplier checked,
                                Consumer<Boolean> handler, Group group) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(checked, "checked");
        Objects.requireNonNull(handler, "handler");
        requireNew(id);
        Command command = new Command(id, title, null, null, group, null, true);
        command.checked = checked;
        command.checkHandler = handler;
        commands.put(id, command);
        command.stateChanged = this::updateGroupVisibility;
        command.orderChanged = this::applyLayout;
        createMenuItem(command);
        applyLayout();
        return command;
    }

    /** Разделитель в «Ещё» между рабочими командами. */
    public void addWorkSeparator() {
        workMenu.addSeparator();
    }

    /** Пункт «Ещё» с подменю; используется для служебных адаптеров. */
    public SubMenu serviceMenu() {
        return serviceMenu;
    }

    /** Пункт «Настройки» с подменю; используется адаптером FilterGrid. */
    public SubMenu settingsMenu() {
        return settingsMenu;
    }

    /** Текущий MenuBar «Ещё» — только для инспекции и совместимых тестов. */
    public MenuBar moreMenuBar() {
        return moreBar;
    }

    public boolean hasCommand(ActionId id) {
        return commands.containsKey(id);
    }

    public Command command(ActionId id) {
        Command command = commands.get(id);
        if (command == null) {
            throw new IllegalArgumentException("Команда «" + id + "» не объявлена");
        }
        return command;
    }

    /** Снимок зарегистрированных команд для инспекции и совместимых тестов. */
    public List<Command> commands() {
        return List.copyOf(commands.values());
    }

    /** Ключ команды по идентичности кнопки; нужен адаптеру совместимой области. */
    public ActionId commandIdForButton(Component component) {
        if (!(component instanceof Button button)) {
            return null;
        }
        for (Command command : commands.values()) {
            if (command.button == button) {
                return command.id;
            }
        }
        return null;
    }

    /**
     * Исключить команду на экземплярном слое. AUTO при этом остаётся AUTO: правило
     * {@code included=false} не превращает состав в явный перечень.
     */
    public void exclude(ActionId id) {
        Command command = command(id);
        command.excludedByInstance = true;
        applyLayout();
    }

    public void setLayout(ListCommandLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout");
        applyLayout();
    }

    public ListCommandLayout layout() {
        return layout;
    }

    /**
     * Перерисовать состояние checked/enabled/visible из текущего состояния формы.
     * Вызывается после изменения выделения, контекста и настроек грида.
     */
    public void refresh() {
        for (Command command : commands.values()) {
            if (command.checked != null) {
                command.checkedValue = command.checked.getAsBoolean();
                if (command.menuItem != null) {
                    command.menuItem.setChecked(command.checkedValue);
                }
            }
            command.applyState();
        }
        updateGroupVisibility();
    }

    private void applyLayout() {
        Set<ActionId> explicit = new LinkedHashSet<>();
        if (layout.composition() == ListCommandLayout.Composition.EXPLICIT) {
            layout.commands().forEach(ref -> explicit.add(ref.id()));
        }
        Set<ActionId> panel = new LinkedHashSet<>();
        if (layout.panelMode() == ListCommandLayout.PanelMode.EXPLICIT) {
            layout.panel().forEach(ref -> panel.add(ref.id()));
        }

        List<Command> panelCommands = new ArrayList<>();
        for (Command command : commands.values()) {
            boolean available = layout.composition() != ListCommandLayout.Composition.EXPLICIT
                || explicit.contains(command.id);
            if (!available) {
                available = false;
            }
            Boolean included = layout.override(command.id()).included();
            command.included = included == null ? available : included;
            if (command.excludedByInstance) {
                command.included = false;
            }

            boolean onPanel = false;
            if (command.button != null && command.included) {
                onPanel = switch (layout.panelMode()) {
                    case ALL -> true;
                    case EXPLICIT -> panel.contains(command.id);
                    case DEFAULT, INHERIT -> defaultPanel(command.id);
                };
                ListCommandLayout.Placement placement = layout.override(command.id()).placement();
                if (placement == ListCommandLayout.Placement.PANEL) {
                    onPanel = true;
                } else if (placement == ListCommandLayout.Placement.MORE) {
                    onPanel = false;
                }
            }
            command.onPanel = onPanel;
            command.presentationTitle = layout.override(command.id()).title() == null
                ? command.title : layout.override(command.id()).title();
            command.view = resolveView(command);
            command.order = layout.override(command.id()).order() == null
                ? defaultOrder(command.id) : layout.override(command.id()).order();
            if (onPanel) {
                panelCommands.add(command);
            }
            command.applyPresentation();
        }

        panelCommands.sort(Comparator.comparingInt((Command c) -> c.order)
            .thenComparingInt(c -> c.definitionOrder).thenComparing(c -> c.id.value()));
        commandRow.removeAll();
        for (Command command : panelCommands) {
            commandRow.add(command.button);
        }
        List<Command> ordered = new ArrayList<>(commands.values());
        ordered.sort(Comparator.comparingInt((Command c) -> c.order)
            .thenComparingInt(c -> c.definitionOrder).thenComparing(c -> c.id.value()));
        for (Command command : ordered) {
            SubMenu menu = switch (command.group) {
                case WORK -> workMenu;
                case SETTINGS -> settingsMenu;
                case SERVICE -> serviceMenu;
            };
            menu.remove(command.menuItem);
            menu.addComponent(command.menuItem);
        }
        workMenu.remove(settingsItem, serviceItem);
        workMenu.addComponent(settingsItem, serviceItem);
        updateGroupVisibility();
        scheduleGroupRefresh();
    }

    private boolean validationPending;

    private void scheduleGroupRefresh() {
        getUI().ifPresent(ui -> {
            if (validationPending) return;
            validationPending = true;
            ui.beforeClientResponse(this, context -> {
                validationPending = false;
                updateGroupVisibility();
            });
        });
    }

    private static int defaultOrder(ActionId id) {
        List<ActionId> primary = List.of(CrudAction.CREATE.id(), CrudAction.COPY.id(),
            CrudAction.EDIT.id(), ActionId.of("change-register-key"), ListUiCommand.VIEWS.id(),
            ActionId.of("report.print"), CrudAction.REFRESH.id());
        int index = primary.indexOf(id);
        return index < 0 ? 100 : index * 10;
    }

    private ListCommandLayout.CommandView resolveView(Command command) {
        ListCommandLayout.CommandView view = layout.override(command.id()).view();
        if (view != ListCommandLayout.CommandView.INHERIT) {
            return view;
        }
        if (command.icon == null) {
            return ListCommandLayout.CommandView.ICON_AND_TEXT;
        }
        if (command.id.equals(CrudAction.COPY.id())
                || command.id.equals(CrudAction.REFRESH.id())
                || command.id.equals(ListUiCommand.VIEWS.id())) {
            return ListCommandLayout.CommandView.ICON_ONLY;
        }
        return ListCommandLayout.CommandView.ICON_AND_TEXT;
    }

    private boolean defaultPanel(ActionId id) {
        if (id.equals(CrudAction.CREATE.id()) || id.equals(CrudAction.COPY.id())
                || id.equals(CrudAction.EDIT.id()) || id.equals(CrudAction.REFRESH.id())
                || id.equals(ListUiCommand.VIEWS.id())) {
            return true;
        }
        if (id.equals(CrudAction.OPEN.id()) || id.equals(CrudAction.DELETE.id())
                || id.equals(ListUiCommand.QUICK_SEARCH.id())
                || id.equals(ListUiCommand.CONDITIONS.id())
                || id.equals(ListUiCommand.FORMATTING.id())
                || id.equals(ListUiCommand.GROUPING.id())
                || id.equals(CrudAction.COPY_LINK.id())
                || id.equals(ListUiCommand.DEVELOPMENT.id())) {
            return false;
        }
        // Прикладное объявленное действие сохраняет прежнее место на панели.
        return true;
    }

    private void createMenuItem(Command command) {
        SubMenu menu = switch (command.group) {
            case WORK -> workMenu;
            case SETTINGS -> settingsMenu;
            case SERVICE -> serviceMenu;
        };
        MenuItem item;
        if (command.checkable) {
            item = menu.addItem(command.title);
            item.setCheckable(true);
            item.setChecked(command.checked == null || command.checked.getAsBoolean());
            item.addClickListener(event -> {
                if (!command.included || !command.visible || !command.enabled) {
                    command.syncChecked();
                    return;
                }
                command.checkedValue = item.isChecked();
                if (command.checkHandler != null) {
                    command.checkHandler.accept(command.checkedValue);
                }
                refresh();
            });
        } else {
            item = menu.addItem(command.title, event -> execute(command.id));
            if (command.icon != null) {
                item.addComponentAsFirst(new Icon(command.icon));
            }
        }
        command.menuItem = item;
        command.menuTooltip = com.vaadin.flow.component.shared.Tooltip.forComponent(item);
        command.menuTooltip.setHoverDelay(400);
        command.menuTooltip.setFocusDelay(0);
        item.getElement().setAttribute("data-command-id", command.id.value());
        if (command.group == Group.WORK) {
            // «Настройки» и «Сервис» всегда остаются в конце «Ещё», после рабочих команд.
            workMenu.remove(settingsItem, serviceItem);
            workMenu.addComponent(settingsItem, serviceItem);
        }
    }

    private void execute(ActionId id) {
        Command command = commands.get(id);
        if (command == null) {
            return;
        }
        command.execute();
    }

    private void requireNew(ActionId id) {
        if (commands.containsKey(id)) {
            throw new IllegalStateException("Команда «" + id + "» уже объявлена в этой панели");
        }
    }

    private void updateGroupVisibility() {
        settingsItem.setVisible(settingsMenu.getItems().stream().anyMatch(Component::isVisible));
        serviceItem.setVisible(serviceMenu.getItems().stream().anyMatch(Component::isVisible));
    }

    /**
     * Клиентский замер собственной light-DOM разметки. Родной overflow MenuBar не
     * используется: у нас постоянное «Ещё» с настройками и сервисными пунктами.
     */
    private static final String OVERFLOW_JS =
        "            const bar = this;\n" +
        "            if (bar.__cbInstalled) {\n" +
        "                if (bar.__cbMeasure) bar.__cbMeasure();\n" +
        "                return;\n" +
        "            }\n" +
        "            const row = bar.querySelector('[data-cb-row]');\n" +
        "            if (!row) return;\n" +
        "            bar.__cbInstalled = true;\n" +
        "\n" +
        "            let frame = 0;\n" +
        "            const moreButton = bar.querySelector('[data-cb-more]');\n" +
        "            const measure = () => {\n" +
        "                frame = 0;\n" +
        "                const items = Array.from(row.querySelectorAll('[data-cb-command]'));\n" +
        "                items.forEach(el => {\n" +
        "                    if (!el.hasAttribute('hidden')) el.style.display = '';\n" +
        "                });\n" +
        "                const available = row.clientWidth;\n" +
        "                const gap = parseFloat(getComputedStyle(row).columnGap || '0') || 0;\n" +
        "                let used = 0;\n" +
        "                let overflowed = false;\n" +
        "                const active = document.activeElement;\n" +
        "                let activeWasHidden = false;\n" +
        "                for (const el of items) {\n" +
        "                    if (el.hasAttribute('hidden')) continue;\n" +
        "                    if (overflowed) {\n" +
        "                        el.style.display = 'none';\n" +
        "                        if (active && (el === active || el.contains(active))) activeWasHidden = true;\n" +
        "                        continue;\n" +
        "                    }\n" +
        "                    const width = el.getBoundingClientRect().width;\n" +
        "                    const next = used === 0 ? width : used + gap + width;\n" +
        "                    if (next > available) {\n" +
        "                        overflowed = true;\n" +
        "                        el.style.display = 'none';\n" +
        "                        if (active && (el === active || el.contains(active))) activeWasHidden = true;\n" +
        "                    } else {\n" +
        "                        used = next;\n" +
        "                    }\n" +
        "                }\n" +
        "                if (activeWasHidden && moreButton && typeof moreButton.focus === 'function') {\n" +
        "                    moreButton.focus();\n" +
        "                }\n" +
        "            };\n" +
        "            const schedule = () => {\n" +
        "                if (!frame) frame = requestAnimationFrame(measure);\n" +
        "            };\n" +
        "            bar.__cbMeasure = schedule;\n" +
        "\n" +
        "            if (bar.__cbResize) bar.__cbResize.disconnect();\n" +
        "            bar.__cbResize = new ResizeObserver(schedule);\n" +
        "            bar.__cbResize.observe(row);\n" +
        "\n" +
        "            if (bar.__cbMutations) bar.__cbMutations.disconnect();\n" +
        "            bar.__cbMutations = new MutationObserver(schedule);\n" +
        "            bar.__cbMutations.observe(row, {\n" +
        "                childList: true,\n" +
        "                subtree: true,\n" +
        "                attributes: true,\n" +
        "                attributeFilter: ['hidden', 'disabled']\n" +
        "            });\n" +
        "\n" +
        "            if (document.fonts && document.fonts.ready) {\n" +
        "                document.fonts.ready.then(schedule);\n" +
        "            }\n" +
        "            bar.__cbDispose = () => {\n" +
        "                if (bar.__cbResize) bar.__cbResize.disconnect();\n" +
        "                if (bar.__cbMutations) bar.__cbMutations.disconnect();\n" +
        "                if (frame) cancelAnimationFrame(frame);\n" +
        "                bar.__cbInstalled = false;\n" +
        "                bar.__cbResize = null;\n" +
        "                bar.__cbMutations = null;\n" +
        "                bar.__cbMeasure = null;\n" +
        "                bar.__cbDispose = null;\n" +
        "            };\n" +
        "            schedule();\n";

    private void installOverflowMeasurement() {
        getElement().executeJs(OVERFLOW_JS);
    }

    /**
     * Одна команда и её два представления. Публичная инспекция ограничена кнопкой и
     * программным click; прямые мутации Button не являются конфигурацией команды.
     */
    public static final class Command implements Serializable {

        private final ActionId id;
        private final String title;
        private String presentationTitle;
        private final VaadinIcon icon;
        private final Runnable handler;
        private final Group group;
        private final Button button;
        private final boolean checkable;
        private int definitionOrder;
        private Runnable stateChanged = () -> {};
        private Runnable orderChanged = () -> {};
        private MenuItem menuItem;
        private com.vaadin.flow.component.shared.Tooltip menuTooltip;
        private String tooltip;
        private BooleanSupplier checked;
        private Consumer<Boolean> checkHandler;
        private boolean checkedValue;
        private boolean included = true;
        private boolean excludedByInstance;
        private boolean visible = true;
        private boolean enabled = true;
        private boolean onPanel;
        private ListCommandLayout.CommandView view = ListCommandLayout.CommandView.ICON_AND_TEXT;
        private int order;


        private Command(ActionId id, String title, VaadinIcon icon, Runnable handler, Group group,
                        Button button, boolean checkable) {
            this.id = id;
            this.title = title;
            this.presentationTitle = title;
            this.icon = icon;
            this.handler = handler;
            this.group = group;
            this.button = button;
            this.checkable = checkable;
        }

        /** Порядок прикладного действия внутри хвоста стандартной последовательности. */
        public Command setDefinitionOrder(int order) {
            this.definitionOrder = order;
            orderChanged.run();
            return this;
        }

        public ActionId id() {
            return id;
        }

        public Button button() {
            return button;
        }

        public MenuItem menuItem() {
            return menuItem;
        }

        public Command setEnabled(boolean enabled) {
            this.enabled = enabled;
            applyState();
            stateChanged.run();
            return this;
        }

        public Command setVisible(boolean visible) {
            this.visible = visible;
            applyState();
            stateChanged.run();
            return this;
        }

        public Command setTooltip(String tooltip) {
            this.tooltip = tooltip;
            updateTooltip();
            return this;
        }

        public boolean isIncluded() {
            return included;
        }

        /** Текущее решение о видимости; не зависит от наличия кнопки. */
        public boolean isVisible() {
            return visible;
        }

        /** Текущее решение о доступности; не зависит от наличия кнопки. */
        public boolean isEnabled() {
            return enabled;
        }

        public boolean isOnPanel() {
            return onPanel;
        }

        private void applyPresentation() {
            if (button != null) {
                boolean iconOnly = view == ListCommandLayout.CommandView.ICON_ONLY && icon != null;
                button.setText(iconOnly ? "" : presentationTitle);
                button.getElement().setAttribute("aria-label", presentationTitle);
                button.getElement().setAttribute("data-cb-view", iconOnly ? "icon" : "label");
                updateTooltip();
            }
            applyState();
            if (menuItem != null) {
                if (!java.util.Objects.equals(menuItem.getText(), presentationTitle)) {
                    menuItem.setText(presentationTitle);
                    if (icon != null) menuItem.addComponentAsFirst(new Icon(icon));
                }
                menuItem.setEnabled(enabled && included);
                menuItem.setVisible(visible && included);
            }
        }

        private void updateTooltip() {
            String text = tooltip == null || tooltip.isBlank() ? "" : tooltip;
            if (menuTooltip != null) {
                menuTooltip.setText(text);
            }
            if (button != null) {
                button.setTooltipText(text.isEmpty()
                    ? (view == ListCommandLayout.CommandView.ICON_ONLY ? presentationTitle : null) : text);
            }
        }

        private void applyState() {
            updateTooltip();
            if (button != null) {
                button.setEnabled(enabled && included);
                button.setVisible(visible && included);
                if (included) {
                    button.getStyle().remove("display");
                }
            }
            if (menuItem != null) {
                menuItem.setEnabled(enabled && included);
                menuItem.setVisible(visible && included);
            }
        }

        private void execute() {
            if (!included || !visible || !enabled) {
                return;
            }
            if (handler != null) {
                handler.run();
            }
        }

        private void syncChecked() {
            if (menuItem != null && checked != null) {
                menuItem.setChecked(checked.getAsBoolean());
            }
        }
    }
}

