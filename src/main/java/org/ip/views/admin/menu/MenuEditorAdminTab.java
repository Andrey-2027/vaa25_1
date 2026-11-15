package org.ip.views.admin.menu;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.contextmenu.SubMenu;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.dnd.GridDropLocation;
import com.vaadin.flow.component.grid.dnd.GridDropMode;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.ipro.form.Dirtyable;
import org.ipro.form.Savable;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.navigation.NavigationAdministrationAccess;
import org.ipro.navigation.NavigationConflictException;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationImportance;
import org.ipro.navigation.NavigationMenu;
import org.ipro.navigation.NavigationMenuService;
import org.ipro.navigation.NavigationStructure;
import org.ipro.rls.RlsReadGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Вкладка «Меню» администрирования: слева предметные области из кода (только чтение), справа выбранное
 * меню. Меню — именованная сущность: «Основное меню» одно, остальные назначаются конкретным пользователям.
 * Правки идут в локальный черновик ({@link MenuDraft}) и сохраняются целиком с проверкой версии;
 * назначение пользователям сохраняется отдельно, сразу.
 */
@SpringComponent
@Scope("prototype")
public class MenuEditorAdminTab extends VerticalLayout implements Dirtyable, Savable {
    private static final Logger log = LoggerFactory.getLogger(MenuEditorAdminTab.class);
    private static final int ASSIGNED_SHOWN = 3;
    static final String NEW_SECTION_TITLE = "Новый раздел";

    /** A row of the menu tree: a section or an entry, by identifier. */
    record MenuRow(boolean section, String id) {}

    private final NavigationMenuService service;
    private final NavigationAdministrationAccess access;
    private final SubsystemRegistry areas;
    private final RlsReadGate readGate;
    private final MenuUserLabels userLabels;
    private final Clock clock = Clock.systemUTC();

    private final Button menuButton = new Button();
    private final Button copyButton = new Button("Копия", VaadinIcon.COPY_O.create());
    private final Button moreButton = new Button(VaadinIcon.ELLIPSIS_DOTS_H.create());
    private final Button previewButton = new Button("Как увидит пользователь", VaadinIcon.EYE.create());
    private final Button saveButton = new Button("Сохранить");
    private final Div banner = new Div();
    private final Div strip = new Div();
    private final TextField search = new TextField();
    private final TreeGrid<MenuPaletteModel.Node> paletteGrid = new TreeGrid<>();
    private final TreeGrid<MenuRow> menuGrid = new TreeGrid<>();
    private final TreeData<MenuRow> menuData = new TreeData<>();
    private final TreeDataProvider<MenuRow> menuProvider = new TreeDataProvider<>(menuData);
    private final TreeData<MenuPaletteModel.Node> paletteData = new TreeData<>();
    private final TreeDataProvider<MenuPaletteModel.Node> paletteProvider = new TreeDataProvider<>(paletteData);
    private final Span menuTitle = new Span();
    private final Span lock = new Span();
    private final Div body = new Div();

    private List<NavigationMenu> menus = List.of();
    private NavigationMenu current;
    private MenuDraft draft;
    private MenuPaletteModel palette;
    private Map<String, NavigationFunction> functions = Map.of();
    private Map<String, Map<Long, Long>> placements = Map.of();
    private final Set<String> paletteToggled = new HashSet<>();
    private final Set<String> menuClosed = new HashSet<>();
    private Object dragged;
    private MenuRow editing;
    private boolean paletteHasData;
    private LazyContextMenu chooserMenu;
    private LazyContextMenu moreMenu;

    public MenuEditorAdminTab(NavigationMenuService service, NavigationAdministrationAccess access,
                              SubsystemRegistry areas, RlsReadGate readGate, MenuUserLabels userLabels) {
        this.service = service;
        this.access = access;
        this.areas = areas;
        this.readGate = readGate;
        this.userLabels = userLabels;

        addClassName("menu-editor");
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        setMinHeight("0");

        add(buildBar(), banner(), strip, lock, buildBody());
        strip.addClassName("menu-editor-strip");
        strip.setVisible(false);
        lock.addClassName("me-info");
        lock.setVisible(false);
        updateControls();
    }

    // ------------------------------------------------------------------ building the screen

    private Component buildBar() {
        var bar = new Div();
        bar.addClassName("menu-editor-bar");

        Span label = new Span("Меню");
        label.addClassName("me-muted");

        menuButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        menuButton.addClassName("menu-editor-chooser");
        menuButton.setAriaLabel("Выбор меню");
        chooserMenu = new LazyContextMenu(menuButton, chooser -> {
            fillChooser(chooser);
            return true;
        });

        copyButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        copyButton.setTooltipText("Создать копию текущего меню");
        copyButton.addClickListener(event -> guard(this::askCopy));

        moreButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON);
        moreButton.setAriaLabel("Ещё");
        moreButton.setTooltipText("Ещё");
        moreMenu = new LazyContextMenu(moreButton, more -> {
            if (current == null) return false;
            fillMore(more);
            return true;
        });

        var spacer = new Div();
        spacer.addClassName("me-spacer");

        previewButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        previewButton.addClickListener(event -> openPreview());

        saveButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> doSave());

        bar.add(label, menuButton, copyButton, moreButton, spacer, previewButton, saveButton);
        return bar;
    }

    private Component banner() {
        banner.addClassName("menu-editor-banner");
        banner.setVisible(false);
        return banner;
    }

    private Component buildBody() {
        body.addClassName("menu-editor-body");

        var split = new SplitLayout(buildPalettePane(), buildMenuPane());
        split.setSizeFull();
        split.setSplitterPosition(50);
        body.add(split);
        return body;
    }

    private Component buildPalettePane() {
        var head = new Div();
        head.addClassName("menu-editor-pane-head");
        Span title = new Span("Предметные области");
        title.addClassName("me-title");
        Span readOnly = new Span("только чтение");
        readOnly.addClassName("me-muted");
        head.add(title, readOnly);

        search.setPlaceholder("Найти список или команду");
        search.setAriaLabel("Найти список или команду");
        search.setPrefixComponent(VaadinIcon.SEARCH.create());
        search.setClearButtonVisible(true);
        search.setWidthFull();
        search.addThemeName("small");
        search.setValueChangeMode(ValueChangeMode.LAZY);
        search.setValueChangeTimeout(250);
        search.addValueChangeListener(event -> rebuildPalette());
        var searchBox = new Div(search);
        searchBox.addClassName("menu-editor-search");

        configureTree(paletteGrid);
        paletteGrid.setDataProvider(paletteProvider);
        paletteGrid.addComponentHierarchyColumn(this::paletteRow).setFlexGrow(1);
        paletteGrid.setRowsDraggable(true);
        paletteGrid.setDragFilter(node -> !(node instanceof MenuPaletteModel.AreaNode area && area.noArea()));
        paletteGrid.addDragStartListener(event -> dragged = event.getDraggedItems().isEmpty()
            ? null : event.getDraggedItems().getFirst());
        paletteGrid.addDragEndListener(event -> dragged = null);
        paletteGrid.addExpandListener(event -> {
            if (event.isFromClient()) event.getItems().forEach(node -> toggled(node, true));
        });
        paletteGrid.addCollapseListener(event -> {
            if (event.isFromClient()) event.getItems().forEach(node -> toggled(node, false));
        });

        var pane = new Div(head, searchBox, paletteGrid);
        pane.addClassNames("menu-editor-pane", "menu-editor-palette");
        return pane;
    }

    private Component buildMenuPane() {
        var head = new Div();
        head.addClassName("menu-editor-pane-head");
        menuTitle.addClassName("me-title");
        Button addSection = new Button("Раздел", VaadinIcon.PLUS.create());
        addSection.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        addSection.setTooltipText("Добавить раздел верхнего уровня");
        addSection.addClickListener(event -> addRootSection());
        head.add(menuTitle, addSection);

        configureTree(menuGrid);
        menuGrid.setDataProvider(menuProvider);
        menuGrid.addComponentHierarchyColumn(this::menuRow).setFlexGrow(1);
        menuGrid.setSelectionMode(com.vaadin.flow.component.grid.Grid.SelectionMode.SINGLE);
        menuGrid.setRowsDraggable(true);
        menuGrid.setDropMode(GridDropMode.ON_TOP_OR_BETWEEN);
        menuGrid.addDragStartListener(event -> dragged = event.getDraggedItems().isEmpty()
            ? null : event.getDraggedItems().getFirst());
        menuGrid.addDragEndListener(event -> dragged = null);
        menuGrid.addDropListener(event -> onDrop(event.getDropTargetItem().orElse(null), event.getDropLocation()));
        menuGrid.addItemDoubleClickListener(event -> startEdit(event.getItem()));
        menuGrid.addExpandListener(event -> {
            if (event.isFromClient()) event.getItems().forEach(row -> menuClosed.remove(row.id()));
        });
        menuGrid.addCollapseListener(event -> {
            if (event.isFromClient()) event.getItems().forEach(row -> menuClosed.add(row.id()));
        });

        Span hint = new Span("Пользователь видит пункт, если есть права. Пустые разделы скрываются. "
            + "Перетащите пункт слева или нажмите →; двойной щелчок — переименовать.");
        hint.addClassName("me-hint");
        var foot = new Div(hint);
        foot.addClassName("menu-editor-pane-foot");

        var pane = new Div(head, menuGrid, foot);
        pane.addClassNames("menu-editor-pane", "menu-editor-menu");
        return pane;
    }

    private static <T> void configureTree(TreeGrid<T> grid) {
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
        grid.addClassName("menu-editor-tree");
        grid.setAllRowsVisible(false);
        grid.setSizeFull();
    }

    // ------------------------------------------------------------------ loading

    /** Перечитать меню, функции и размещения; вызывается при выборе вкладки. */
    public void refresh() {
        if (!access.isAdministrator()) {
            showLock("Доступно только администратору");
            return;
        }
        try {
            reload(current == null ? null : current.id());
        } catch (AccessDeniedException denied) {
            showLock("Доступно только администратору");
        } catch (RuntimeException failure) {
            log.error("Cannot load the menu editor", failure);
            showLock("Не удалось загрузить меню. Обновите страницу или обратитесь к администратору.");
        }
    }

    private void showLock(String text) {
        lock.setText(text);
        lock.setVisible(true);
        body.setVisible(false);
        strip.setVisible(false);
        banner.setVisible(false);
        current = null;
        draft = null;
        updateControls();
    }

    private void reload(Long preferredId) {
        lock.setVisible(false);
        body.setVisible(true);
        menus = service.menus();
        var discovered = service.functions();
        functions = new LinkedHashMap<>();
        discovered.forEach(found -> functions.put(found.function().key(), found.function()));
        placements = service.placements();
        palette = new MenuPaletteModel(areas.getRoots(), discovered, clock);
        paletteHasData = true;
        var target = menus.stream().filter(menu -> Objects.equals(preferredId, menu.id())).findFirst()
            .or(() -> menus.stream().filter(NavigationMenu::main).findFirst())
            .or(() -> menus.stream().findFirst());
        if (target.isEmpty()) {
            showLock("Основное меню ещё не собрано: перезапустите приложение.");
            return;
        }
        open(target.get());
    }

    private void open(NavigationMenu menu) {
        current = menu;
        draft = new MenuDraft(service.structure(menu.id()), key -> Optional.ofNullable(functions.get(key)));
        editing = null;
        menuClosed.clear();
        banner.setVisible(false);
        menuGrid.deselectAll();
        updateStrip();
        rebuildPalette();
        changed();
    }

    // ------------------------------------------------------------------ state

    @Override
    public boolean isDirty() { return draft != null && draft.dirty(); }

    @Override
    public String getCloseConfirmMessage() {
        return "Есть несохранённые изменения меню «" + (current == null ? "" : current.name()) + "».";
    }

    @Override
    public boolean doSave() {
        if (draft == null || !draft.dirty()) return true;
        try {
            var saved = service.save(current.id(), draft.version(), draft.toStructure());
            menus = service.menus();
            placements = service.placements();
            current = menus.stream().filter(menu -> menu.id() == current.id()).findFirst().orElse(current);
            draft = new MenuDraft(saved, key -> Optional.ofNullable(functions.get(key)));
            editing = null;
            banner.setVisible(false);
            menuGrid.deselectAll();
            changed();
            Notification.show("Меню сохранено");
            return true;
        } catch (NavigationConflictException conflict) {
            showConflict(conflict.getMessage());
            return false;
        } catch (AccessDeniedException denied) {
            Notification.show("Нет доступа к настройке меню");
            return false;
        } catch (RuntimeException failure) {
            log.error("Cannot save the menu", failure);
            Notification.show("Не удалось сохранить меню: " + failure.getMessage());
            return false;
        }
    }

    /** Runs the action at once, or after the administrator decides what to do with unsaved edits. */
    public void guard(Runnable proceed) {
        if (!isDirty()) {
            proceed.run();
            return;
        }
        var dialog = new ConfirmDialog();
        dialog.setHeader("Несохранённые изменения");
        dialog.setText(getCloseConfirmMessage());
        dialog.setConfirmText("Сохранить");
        dialog.addConfirmListener(event -> {
            if (doSave()) proceed.run();
        });
        dialog.setRejectable(true);
        dialog.setRejectText("Отбросить");
        dialog.addRejectListener(event -> {
            discard();
            proceed.run();
        });
        dialog.setCancelable(true);
        dialog.setCancelText("Отмена");
        dialog.open();
    }

    private void discard() {
        if (current == null) return;
        draft = new MenuDraft(service.structure(current.id()), key -> Optional.ofNullable(functions.get(key)));
        editing = null;
        changed();
    }

    private void showConflict(String message) {
        banner.removeAll();
        Span text = new Span(message + " Ваши правки остались в черновике.");
        Button reload = new Button("Обновить из базы", event -> confirmReload());
        reload.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY_INLINE);
        banner.add(VaadinIcon.WARNING.create(), text, reload);
        banner.setVisible(true);
    }

    private void confirmReload() {
        if (!isDirty()) {
            reloadCurrent();
            return;
        }
        var dialog = new ConfirmDialog();
        dialog.setHeader("Обновить из базы?");
        dialog.setText("Несохранённые правки будут потеряны.");
        dialog.setConfirmText("Обновить");
        dialog.setCancelable(true);
        dialog.setCancelText("Отмена");
        dialog.addConfirmListener(event -> reloadCurrent());
        dialog.open();
    }

    private void reloadCurrent() {
        try {
            reload(current == null ? null : current.id());
        } catch (RuntimeException failure) {
            log.error("Cannot reload the menu", failure);
            Notification.show("Не удалось обновить меню: " + failure.getMessage());
        }
    }

    private void changed() {
        rebuildMenuTree();
        updateMarks();
        updateControls();
    }

    private void updateControls() {
        boolean ready = current != null && draft != null;
        saveButton.setEnabled(ready && draft.dirty());
        copyButton.setEnabled(ready);
        moreButton.setEnabled(ready);
        previewButton.setEnabled(ready);
        menuButton.setEnabled(ready);
        menuButton.setText(current == null ? "" : current.name() + (current.main() ? "" : suffix(current)));
        menuButton.setIcon(current != null && current.main() ? VaadinIcon.STAR.create() : null);
        menuButton.setSuffixComponent(VaadinIcon.CHEVRON_DOWN_SMALL.create());
        menuTitle.setText(current == null ? "" : current.name() + (ready && draft.dirty() ? " •" : ""));
    }

    private static String suffix(NavigationMenu menu) {
        return menu.assignedUsers() == 0 ? "" : " · " + menu.assignedUsers();
    }

    private void updateStrip() {
        strip.removeAll();
        if (current == null || current.main()) {
            strip.setVisible(false);
            return;
        }
        var keys = new ArrayList<>(service.assignees(current.id()));
        var names = keys.stream().map(userLabels::label).sorted().toList();
        Span who = new Span(names.isEmpty() ? "Назначено: никому" : "Назначено: " + String.join(", ", names.subList(0,
            Math.min(ASSIGNED_SHOWN, names.size()))) + (names.size() > ASSIGNED_SHOWN
            ? " и ещё " + (names.size() - ASSIGNED_SHOWN) : ""));
        Button change = new Button("изменить", event -> openAssignment());
        change.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY_INLINE);
        Span rest = new Span("остальные — «" + mainName() + "»");
        rest.addClassNames("me-muted", "me-push");
        strip.add(VaadinIcon.USERS.create(), who, change, rest);
        strip.setVisible(true);
    }

    private String mainName() {
        return menus.stream().filter(NavigationMenu::main).map(NavigationMenu::name).findFirst().orElse("Основное меню");
    }

    // ------------------------------------------------------------------ menu chooser and actions

    private void fillChooser(ContextMenu chooser) {
        for (NavigationMenu menu : menus) {
            var row = new Div();
            row.addClassName("me-row");
            Icon mark = menu.main() ? VaadinIcon.STAR.create() : current != null && menu.id() == current.id()
                ? VaadinIcon.CHECK.create() : null;
            Span mark0 = new Span();
            mark0.addClassName("me-mark");
            if (mark != null) mark0.add(mark);
            Span name = new Span(menu.name());
            name.addClassName("me-title");
            Span note = new Span(menu.main() ? "все остальные" : menu.assignedUsers() + " польз.");
            note.addClassName("me-muted");
            row.add(mark0, name, note);
            chooser.addItem(row, event -> {
                if (current != null && menu.id() == current.id()) return;
                guard(() -> open(menu));
            });
        }
        chooser.addSeparator();
        chooser.addItem(iconRow(VaadinIcon.COPY_O, "Копия текущего…", null), event -> guard(this::askCopy));
        chooser.addItem(iconRow(VaadinIcon.PLUS, "Пустое меню…", null), event -> guard(this::askEmpty));
    }

    private void fillMore(ContextMenu more) {
        more.addItem(iconRow(VaadinIcon.PENCIL, "Переименовать меню", null), event -> askRename());
        var assign = more.addItem(iconRow(VaadinIcon.USERS, "Кому назначено…", null), event -> openAssignment());
        assign.setEnabled(!current.main());
        more.addItem(iconRow(VaadinIcon.REFRESH, "Обновить из базы", null), event -> confirmReload());
        more.addSeparator();
        more.addItem(iconRow(VaadinIcon.DOWNLOAD, "Выгрузить", null), event -> openExport(current));
        more.addItem(iconRow(VaadinIcon.UPLOAD, "Загрузить как новое меню", null),
            event -> guard(() -> openImport(null)));
        more.addItem(iconRow(VaadinIcon.UPLOAD, "Заменить это меню", null),
            event -> guard(() -> openImport(current)));
        more.addSeparator();
        var delete = more.addItem(iconRow(VaadinIcon.TRASH, "Удалить меню",
            current.main() ? "основное меню удалить нельзя" : "его пользователи вернутся к основному"),
            event -> askDelete());
        delete.setEnabled(!current.main());
    }

    private void openExport(NavigationMenu menu) {
        long menuId = menu.id();
        String menuName = menu.name();
        var download = new com.vaadin.flow.component.html.Anchor();
        download.setText("Скачать JSON");
        download.setHref((com.vaadin.flow.server.streams.DownloadHandler) event -> {
            final byte[] bytes;
            try {
                bytes = MenuJson.encode(menuName, service.structure(menuId));
            } catch (AccessDeniedException denied) {
                event.getResponse().setStatus(403);
                return;
            }
            String filename = menuName.replaceAll("[^\\p{L}\\p{N} ._-]", "-");
            event.setFileName("menu-" + filename + "-" + java.time.LocalDate.now(clock) + ".json");
            event.setContentType("application/json; charset=UTF-8");
            event.setContentLength(bytes.length);
            event.getResponse().setHeader("Cache-Control", "no-store");
            event.getOutputStream().write(bytes);
        });
        var dialog = new Dialog();
        dialog.setHeaderTitle("Выгрузить меню «" + menuName + "»");
        dialog.add(new Span("В файл попадёт сохранённая структура меню."), new Div(download));
        dialog.getFooter().add(new Button("Закрыть", event -> dialog.close()));
        dialog.open();
    }

    private void openImport(NavigationMenu target) {
        new MenuImportDialog(service, target, id -> {
            reload(id);
            Notification.show("Меню загружено");
        }).open();
    }

    private static Component iconRow(VaadinIcon icon, String text, String note) {
        var row = new Div();
        row.addClassName("me-row");
        Icon image = icon.create();
        image.addClassName("me-muted");
        Span title = new Span(text);
        title.addClassName("me-title");
        row.add(image, title);
        if (note != null) {
            Span hint = new Span(note);
            hint.addClassName("me-muted");
            row.add(hint);
        }
        return row;
    }

    private void askCopy() {
        if (current == null) return;
        long source = current.id();
        askName("Копия меню", "Копия — " + current.name(), "Создать", name -> {
            var created = service.copy(source, name);
            afterCreated(created);
            return null;
        });
    }

    private void askEmpty() {
        askName("Пустое меню", "", "Создать", name -> {
            afterCreated(service.createEmpty(name));
            return null;
        });
    }

    private void afterCreated(NavigationMenu created) {
        reload(created.id());
        Notification.show("Создано меню «" + created.name() + "»");
    }

    private void askRename() {
        if (current == null) return;
        askName("Переименовать меню", current.name(), "Переименовать", name -> {
            renameCurrent(name);
            return null;
        });
    }

    /** Renames the open menu; the version moves on without a change of content. */
    void renameCurrent(String name) {
        long id = current.id();
        var renamed = service.rename(id, draft.version(), name);
        draft.version(renamed.version());
        menus = service.menus();
        current = menus.stream().filter(menu -> menu.id() == id).findFirst().orElse(renamed);
        updateControls();
        updateStrip();
    }

    /** A dialog for a menu name; the action returns an error text to keep the dialog open, or {@code null}. */
    private void askName(String header, String initial, String okText, Function<String, String> action) {
        var dialog = new Dialog();
        dialog.setHeaderTitle(header);
        dialog.setWidth("min(380px, 96vw)");
        var field = new TextField("Название");
        field.setValue(initial);
        field.setMaxLength(MenuDraft.MAX_TITLE);
        field.setWidthFull();
        field.setRequiredIndicatorVisible(true);
        dialog.add(field);
        Runnable submit = () -> {
            String name = field.getValue() == null ? "" : field.getValue().strip();
            if (name.isEmpty()) {
                field.setInvalid(true);
                field.setErrorMessage("Введите название");
                return;
            }
            try {
                String error = action.apply(name);
                if (error != null) {
                    field.setInvalid(true);
                    field.setErrorMessage(error);
                    return;
                }
                dialog.close();
            } catch (NavigationConflictException conflict) {
                field.setInvalid(true);
                field.setErrorMessage("Меню с таким названием уже есть или меню изменено другим администратором");
            } catch (IllegalArgumentException invalid) {
                field.setInvalid(true);
                field.setErrorMessage(invalid.getMessage());
            } catch (RuntimeException failure) {
                log.error("Menu operation failed", failure);
                field.setInvalid(true);
                field.setErrorMessage("Не удалось выполнить действие");
            }
        };
        field.addKeyDownListener(Key.ENTER, event -> submit.run());
        Button ok = new Button(okText, event -> submit.run());
        ok.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Отмена", event -> dialog.close()), ok);
        // Focus and select the text once the dialog is on the page, so a copy name can be typed over.
        dialog.addOpenedChangeListener(event -> {
            if (event.isOpened()) {
                field.focus();
                field.getElement().executeJs("setTimeout(() => this.select(), 0)");
            }
        });
        dialog.open();
    }

    private void askDelete() {
        if (current == null || current.main()) return;
        var menu = current;
        var dialog = new ConfirmDialog();
        dialog.setHeader("Удалить меню «" + menu.name() + "»?");
        dialog.setText(menu.assignedUsers() == 0 ? "Меню будет удалено."
            : "Меню будет удалено. Его пользователи (" + menu.assignedUsers() + ") вернутся к основному меню.");
        dialog.setConfirmText("Удалить");
        dialog.setConfirmButtonTheme("error primary");
        dialog.setCancelable(true);
        dialog.setCancelText("Отмена");
        dialog.addConfirmListener(event -> deleteCurrent());
        dialog.open();
    }

    /** Deletes the open named menu; its users return to the main menu. */
    void deleteCurrent() {
        if (current == null || current.main()) return;
        try {
            service.delete(current.id(), draft.version());
            reload(null);
            Notification.show("Меню удалено");
        } catch (NavigationConflictException conflict) {
            showConflict(conflict.getMessage());
        } catch (RuntimeException failure) {
            log.error("Cannot delete the menu", failure);
            Notification.show("Не удалось удалить меню: " + failure.getMessage());
        }
    }

    private void openAssignment() {
        if (current == null || current.main()) return;
        var names = new HashMap<Long, String>();
        menus.forEach(menu -> names.put(menu.id(), menu.name()));
        try {
            new MenuAssignmentDialog(service, current, names, userLabels::label, () -> {
                try {
                    menus = service.menus();
                    current = menus.stream().filter(menu -> menu.id() == current.id()).findFirst().orElse(current);
                    updateStrip();
                    updateControls();
                } catch (RuntimeException failure) {
                    log.error("Cannot refresh the assignment", failure);
                }
            }).open();
        } catch (RuntimeException failure) {
            log.error("Cannot open the assignment dialog", failure);
            Notification.show("Не удалось открыть назначение: " + failure.getMessage());
        }
    }

    private void openPreview() {
        if (current == null) return;
        new MenuPreviewDialog(service, readGate, isDirty()).open();
    }

    // ------------------------------------------------------------------ palette

    private void rebuildPalette() {
        if (palette == null || !paletteHasData) return;
        String text = search.getValue();
        boolean searching = text != null && !text.isBlank();
        var nodes = palette.filter(text);
        applyMarks();
        paletteData.clear();
        addNodes(paletteData, null, nodes);
        paletteProvider.refreshAll();
        var open = new ArrayList<MenuPaletteModel.Node>();
        collectOpen(nodes, searching, open);
        paletteGrid.expand(open);
    }

    private static void addNodes(TreeData<MenuPaletteModel.Node> data, MenuPaletteModel.Node parent,
                                 List<? extends MenuPaletteModel.Node> nodes) {
        for (var node : nodes) {
            data.addItem(parent, node);
            addNodes(data, node, node.children());
        }
    }

    private void collectOpen(List<? extends MenuPaletteModel.Node> nodes, boolean all, List<MenuPaletteModel.Node> open) {
        for (var node : nodes) {
            if (node.children().isEmpty()) continue;
            if (all || isOpen(node)) open.add(node);
            collectOpen(node.children(), all, open);
        }
    }

    private boolean isOpen(MenuPaletteModel.Node node) {
        return node instanceof MenuPaletteModel.AreaNode != paletteToggled.contains(node.id());
    }

    private void toggled(MenuPaletteModel.Node node, boolean expanded) {
        boolean defaultOpen = node instanceof MenuPaletteModel.AreaNode;
        if (expanded == defaultOpen) paletteToggled.remove(node.id());
        else paletteToggled.add(node.id());
    }

    /** Marks read at display time: refresh the rows after every change of the draft. */
    private void updateMarks() {
        if (palette == null || draft == null) return;
        applyMarks();
        paletteProvider.refreshAll();
    }

    private void applyMarks() {
        if (palette == null || draft == null) return;
        var counts = new HashMap<String, Integer>();
        functions.keySet().forEach(key -> {
            int count = draft.placements(key);
            if (count > 0) counts.put(key, count);
        });
        var elsewhere = new HashSet<String>();
        long currentId = current == null ? -1 : current.id();
        placements.forEach((key, byMenu) -> {
            if (byMenu.entrySet().stream().anyMatch(entry -> entry.getKey() != currentId && entry.getValue() > 0)) {
                elsewhere.add(key);
            }
        });
        palette.marks(counts, elsewhere);
    }

    private Component paletteRow(MenuPaletteModel.Node node) {
        var row = new Div();
        row.addClassName("me-row");
        if (node instanceof MenuPaletteModel.AreaNode area) {
            row.addClassName("me-area");
            Span title = new Span(area.title());
            title.addClassNames("me-title", "me-strong");
            row.add(title);
            if (!area.noArea() && area.hasLists()) {
                row.add(addButton("Добавить всю область в меню", event -> addAreaHere(area)));
            }
            return row;
        }
        var function = ((MenuPaletteModel.FunctionNode) node).function();
        var functionNode = (MenuPaletteModel.FunctionNode) node;
        if (function.command()) {
            Icon bolt = VaadinIcon.BOLT.create();
            bolt.addClassName("me-muted");
            row.add(bolt);
        }
        Span title = new Span(function.title());
        title.addClassName("me-title");
        row.add(title);
        int count = palette.count(function);
        if (functionNode.related().size() > 0 && !function.command()) {
            Span nested = new Span("⚡" + functionNode.related().size());
            nested.addClassName("me-muted");
            nested.getElement().setProperty("title", "Варианты и команды: " + functionNode.related().size());
            row.add(nested);
        }
        if (count > 0) {
            Icon check = VaadinIcon.CHECK.create();
            check.addClassName("me-muted");
            row.add(check);
            if (count > 1) {
                Span times = new Span("×" + count);
                times.addClassName("me-muted");
                row.add(times);
            }
        }
        if (palette.isNew(functionNode)) {
            Span tag = new Span("новое");
            tag.addClassNames("me-tag", "me-tag-warn");
            row.add(tag);
        }
        var add = addButton("Добавить в выбранный раздел", event -> addFunctionHere(function));
        if (count > 0) add.addClassName("me-add-placed");
        row.add(add);
        return row;
    }

    private static Button addButton(String tooltip, com.vaadin.flow.component.ComponentEventListener<
            com.vaadin.flow.component.ClickEvent<Button>> listener) {
        var button = new Button(VaadinIcon.ARROW_RIGHT.create(), listener);
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON);
        button.addClassName("me-add");
        button.setAriaLabel(tooltip);
        button.setTooltipText(tooltip);
        return button;
    }

    // ------------------------------------------------------------------ menu tree

    private void rebuildMenuTree() {
        if (draft == null) {
            menuData.clear();
            menuProvider.refreshAll();
            return;
        }
        var selected = menuGrid.getSelectedItems().stream().findFirst().orElse(null);
        var open = new ArrayList<MenuRow>();
        menuData.clear();
        for (var section : draft.rootSections()) addSectionRows(menuData, null, section, open);
        menuProvider.refreshAll();
        menuGrid.expand(open);
        if (selected != null && exists(selected)) menuGrid.select(selected);
        menuTitle.setText(current == null ? "" : current.name() + (draft.dirty() ? " •" : ""));
    }

    private void addSectionRows(TreeData<MenuRow> data, MenuRow parent, NavigationStructure.Section section,
                                List<MenuRow> open) {
        var row = new MenuRow(true, section.id());
        data.addItem(parent, row);
        for (var entry : draft.entries(section.id())) data.addItem(row, new MenuRow(false, entry.id()));
        for (var child : draft.childSections(section.id())) addSectionRows(data, row, child, open);
        if (!menuClosed.contains(section.id())) open.add(row);
    }

    private boolean exists(MenuRow row) {
        return row.section() ? draft.section(row.id()).isPresent() : draft.entry(row.id()).isPresent();
    }

    private Component menuRow(MenuRow row) {
        if (editing != null && editing.equals(row)) return editor(row);
        var line = new Div();
        line.addClassName("me-row");
        if (row.section()) {
            var section = draft.section(row.id()).orElse(null);
            if (section == null) return line;
            line.addClassName("me-section");
            Icon grip = VaadinIcon.ELLIPSIS_DOTS_V.create();
            grip.addClassNames("me-muted", "me-grip");
            Span title = new Span(section.title());
            title.addClassNames("me-title", "me-strong");
            line.add(grip, iconOf(section.icon()), title);
            if (section.areaKey() != null) {
                Span link = new Span(VaadinIcon.LINK.create());
                link.addClassNames("me-tag", "me-tag-accent");
                link.getElement().setProperty("title", "Связан с областью: её новые сущности попадают в этот раздел");
                line.add(link);
            }
        } else {
            var entry = draft.entry(row.id()).orElse(null);
            if (entry == null) return line;
            var function = draft.function(entry);
            line.addClassName("me-entry");
            if (function.map(NavigationFunction::command).orElse(false)) {
                Icon bolt = VaadinIcon.BOLT.create();
                bolt.addClassName("me-bolt");
                line.add(bolt);
            }
            Span title = new Span(draft.title(entry));
            title.addClassName("me-title");
            if (entry.importance() == NavigationImportance.IMPORTANT) title.addClassName("me-strong");
            if (entry.importance() == NavigationImportance.SEE_ALSO) title.addClassName("me-muted");
            if (draft.renamed(entry) && function.isPresent()) {
                title.getElement().setProperty("title", "Исходное название: " + function.get().title());
            }
            line.add(title);
            if (entry.importance() == NavigationImportance.SEE_ALSO) {
                Span also = new Span("см. также");
                also.addClassNames("me-muted", "me-small");
                line.add(also);
            }
            if (function.isEmpty()) {
                Span gone = new Span("функции больше нет");
                gone.addClassNames("me-tag", "me-tag-warn");
                line.add(gone);
            }
        }
        var dots = new Button(VaadinIcon.ELLIPSIS_DOTS_H.create());
        dots.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON);
        dots.addClassName("me-dots");
        dots.setAriaLabel("Действия");
        new LazyContextMenu(dots, menu -> {
            if (row.section()) fillSectionMenu(menu, row);
            else fillEntryMenu(menu, row);
            return true;
        });
        line.add(dots);
        return line;
    }

    private static Icon iconOf(String name) {
        try {
            Icon icon = VaadinIcon.valueOf(name).create();
            icon.addClassName("me-section-icon");
            return icon;
        } catch (IllegalArgumentException | NullPointerException unknown) {
            Icon icon = VaadinIcon.FOLDER_O.create();
            icon.addClassName("me-section-icon");
            return icon;
        }
    }

    private Component editor(MenuRow row) {
        String initial = row.section() ? draft.section(row.id()).map(NavigationStructure.Section::title).orElse("")
            : draft.entry(row.id()).map(draft::title).orElse("");
        var field = new TextField();
        field.setValue(initial);
        field.setMaxLength(MenuDraft.MAX_TITLE);
        field.setWidthFull();
        field.addThemeName("small");
        field.setAriaLabel("Название");
        field.addKeyDownListener(Key.ENTER, event -> finishEdit(row, field.getValue()));
        field.addKeyDownListener(Key.ESCAPE, event -> cancelEdit());
        field.addBlurListener(event -> finishEdit(row, field.getValue()));
        field.addAttachListener(event -> field.focus());
        var line = new Div(field);
        line.addClassNames("me-row", "me-editing");
        return line;
    }

    void startEdit(MenuRow row) {
        if (draft == null) return;
        editing = row;
        menuGrid.getDataProvider().refreshItem(row);
    }

    private void finishEdit(MenuRow row, String text) {
        if (editing == null || !editing.equals(row)) return;
        editing = null;
        try {
            if (row.section()) draft.renameSection(row.id(), text);
            else draft.renameEntry(row.id(), text);
        } catch (IllegalArgumentException invalid) {
            Notification.show(invalid.getMessage());
        }
        changed();
    }

    private void cancelEdit() {
        if (editing == null) return;
        var row = editing;
        editing = null;
        menuGrid.getDataProvider().refreshItem(row);
    }

    // ------------------------------------------------------------------ row menus

    private void fillSectionMenu(ContextMenu menu, MenuRow row) {
        var section = draft.section(row.id()).orElse(null);
        if (section == null) return;
        menu.addItem(iconRow(VaadinIcon.PENCIL, "Переименовать", null), event -> startEdit(row));
        menu.addItem(iconRow(VaadinIcon.PLUS, "Добавить подраздел", null), event -> addChildSection(section.id()));
        menu.addItem(iconRow(VaadinIcon.PICTURE, "Значок…", null), event -> askIcon(section.id()));
        if (section.areaKey() != null) {
            menu.addItem(iconRow(VaadinIcon.UNLINK, "Отвязать от области",
                "новые сущности области сюда не попадут"), event -> {
                draft.unlinkArea(section.id());
                changed();
            });
        }
        menu.addSeparator();
        menu.addItem(iconRow(VaadinIcon.ARROW_UP, "Выше", null), event -> moveBy(row, -1));
        menu.addItem(iconRow(VaadinIcon.ARROW_DOWN, "Ниже", null), event -> moveBy(row, 1));
        var move = menu.addItem(iconRow(VaadinIcon.ARROW_RIGHT, "Перенести в…", null));
        fillMoveTargets(move.getSubMenu(), row);
        menu.addSeparator();
        menu.addItem(iconRow(VaadinIcon.TRASH, "Удалить раздел…", null), event -> askRemoveSection(section.id()));
    }

    private void fillEntryMenu(ContextMenu menu, MenuRow row) {
        var entry = draft.entry(row.id()).orElse(null);
        if (entry == null) return;
        menu.addItem(iconRow(VaadinIcon.PENCIL, "Переименовать", null), event -> startEdit(row));
        var importance = menu.addItem(iconRow(VaadinIcon.STAR_O, "Важность", null));
        for (NavigationImportance value : NavigationImportance.values()) {
            var item = importance.getSubMenu().addItem(importanceTitle(value) + (entry.importance() == value ? "  ✓" : ""),
                event -> {
                    draft.setImportance(entry.id(), value);
                    changed();
                });
            item.setEnabled(entry.importance() != value);
        }
        var reset = menu.addItem(iconRow(VaadinIcon.REFRESH, "Вернуть исходный заголовок", null), event -> {
            draft.resetEntryTitle(entry.id());
            changed();
        });
        reset.setEnabled(draft.renamed(entry));
        menu.addSeparator();
        menu.addItem(iconRow(VaadinIcon.ARROW_UP, "Выше", null), event -> moveBy(row, -1));
        menu.addItem(iconRow(VaadinIcon.ARROW_DOWN, "Ниже", null), event -> moveBy(row, 1));
        var move = menu.addItem(iconRow(VaadinIcon.ARROW_RIGHT, "Перенести в…", null));
        fillMoveTargets(move.getSubMenu(), row);
        menu.addSeparator();
        menu.addItem(iconRow(VaadinIcon.CLOSE_SMALL, "Убрать из меню", null), event -> {
            draft.removeEntry(entry.id());
            changed();
        });
    }

    static String importanceTitle(NavigationImportance importance) {
        return switch (importance) {
            case IMPORTANT -> "Важное";
            case NORMAL -> "Обычное";
            case SEE_ALSO -> "См. также";
        };
    }

    /** The keyboard way to move: a section goes under another section (or to the top level), an entry into a section. */
    private void fillMoveTargets(SubMenu sub, MenuRow row) {
        if (row.section()) {
            var section = draft.section(row.id()).orElseThrow();
            if (section.parentId() != null) {
                sub.addItem("Верхний уровень", event -> moveSectionTo(row.id(), null));
            }
            for (var path : draft.sectionPaths()) {
                if (draft.isSelfOrDescendant(path.section().id(), row.id())
                        || path.section().id().equals(section.parentId())) continue;
                sub.addItem(path.path(), event -> moveSectionTo(row.id(), path.section().id()));
            }
        } else {
            var entry = draft.entry(row.id()).orElseThrow();
            for (var path : draft.sectionPaths()) {
                if (path.section().id().equals(entry.sectionId())) continue;
                sub.addItem(path.path(), event -> {
                    draft.moveEntry(entry.id(), path.section().id(), -1);
                    changed();
                });
            }
        }
    }

    private void moveSectionTo(String id, String parentId) {
        if (!draft.moveSection(id, parentId, -1)) {
            Notification.show("Раздел нельзя перенести внутрь самого себя");
            return;
        }
        changed();
    }

    private void moveBy(MenuRow row, int delta) {
        if (row.section()) {
            var section = draft.section(row.id()).orElseThrow();
            var siblings = draft.childSections(section.parentId());
            int target = siblings.indexOf(section) + delta;
            if (target < 0 || target >= siblings.size()) return;
            draft.moveSection(section.id(), section.parentId(), target);
        } else {
            var entry = draft.entry(row.id()).orElseThrow();
            var siblings = draft.entries(entry.sectionId());
            int target = siblings.indexOf(entry) + delta;
            if (target < 0 || target >= siblings.size()) return;
            draft.moveEntry(entry.id(), entry.sectionId(), target);
        }
        changed();
    }

    private void askIcon(String sectionId) {
        var section = draft.section(sectionId).orElse(null);
        if (section == null) return;
        var dialog = new Dialog();
        dialog.setHeaderTitle("Значок раздела");
        dialog.setWidth("min(360px, 96vw)");
        var box = new ComboBox<VaadinIcon>("Значок");
        box.setItems(VaadinIcon.values());
        box.setItemLabelGenerator(VaadinIcon::name);
        box.setRenderer(new com.vaadin.flow.data.renderer.ComponentRenderer<>(icon -> {
            var item = new Div(icon.create(), new Span(icon.name()));
            item.addClassName("me-row");
            return item;
        }));
        box.setWidthFull();
        try {
            box.setValue(VaadinIcon.valueOf(section.icon()));
        } catch (IllegalArgumentException unknown) {
            box.setValue(VaadinIcon.FOLDER_O);
        }
        dialog.add(box);
        Button ok = new Button("Готово", event -> {
            if (box.getValue() != null) {
                draft.setSectionIcon(sectionId, box.getValue().name());
                changed();
            }
            dialog.close();
        });
        ok.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Отмена", event -> dialog.close()), ok);
        dialog.open();
    }

    private void askRemoveSection(String sectionId) {
        var section = draft.section(sectionId).orElse(null);
        if (section == null) return;
        int content = draft.contentCount(sectionId);
        if (content == 0) {
            draft.removeSection(sectionId);
            changed();
            return;
        }
        var dialog = new ConfirmDialog();
        dialog.setHeader("Удалить раздел «" + section.title() + "»?");
        dialog.setText("Вместе с разделом уйдёт его содержимое (" + content + "). Сами списки и команды останутся"
            + " в предметных областях, их можно добавить заново.");
        dialog.setConfirmText("Удалить");
        dialog.setConfirmButtonTheme("error primary");
        dialog.setCancelable(true);
        dialog.setCancelText("Отмена");
        dialog.addConfirmListener(event -> {
            draft.removeSection(sectionId);
            changed();
        });
        dialog.open();
    }

    // ------------------------------------------------------------------ adding

    private void addRootSection() {
        if (draft == null) return;
        var section = draft.addSection(null, -1, NEW_SECTION_TITLE);
        changed();
        var row = new MenuRow(true, section.id());
        menuGrid.select(row);
        startEdit(row);
    }

    private void addChildSection(String parentId) {
        var section = draft.addSection(parentId, -1, NEW_SECTION_TITLE);
        menuClosed.remove(parentId);
        changed();
        var row = new MenuRow(true, section.id());
        menuGrid.select(row);
        startEdit(row);
    }

    /** The place for a function: after the selected entry, at the end of the selected section. */
    private Optional<Slot> entrySlotFromSelection() {
        var selected = menuGrid.getSelectedItems().stream().findFirst();
        if (selected.isEmpty() || !exists(selected.get())) return Optional.empty();
        var row = selected.get();
        if (row.section()) return Optional.of(new Slot(row.id(), -1));
        var entry = draft.entry(row.id()).orElseThrow();
        return Optional.of(new Slot(entry.sectionId(), draft.entries(entry.sectionId()).indexOf(entry) + 1));
    }

    /** The place for an area: inside the selected section, else at the top level. */
    private Slot areaSlotFromSelection() {
        var selected = menuGrid.getSelectedItems().stream().findFirst();
        if (selected.isEmpty() || !exists(selected.get())) return new Slot(null, -1);
        var row = selected.get();
        if (row.section()) return new Slot(row.id(), -1);
        return new Slot(draft.entry(row.id()).orElseThrow().sectionId(), -1);
    }

    private record Slot(String parentId, int index) {}

    void addFunctionHere(NavigationFunction function) {
        if (draft == null) return;
        var slot = entrySlotFromSelection();
        if (slot.isEmpty()) {
            Notification.show("Выберите раздел справа, затем нажмите →");
            return;
        }
        addFunction(function, slot.get());
    }

    private void addFunction(NavigationFunction function, Slot slot) {
        var entry = draft.addFunction(function, slot.parentId(), slot.index());
        menuClosed.remove(slot.parentId());
        changed();
        menuGrid.select(new MenuRow(false, entry.id()));
    }

    void addAreaHere(MenuPaletteModel.AreaNode area) {
        if (draft == null) return;
        addArea(area, areaSlotFromSelection());
    }

    private void addArea(MenuPaletteModel.AreaNode area, Slot slot) {
        var added = draft.addArea(area, slot.parentId(), slot.index());
        if (added.empty()) {
            Notification.show("«" + area.title() + "»: все списки области уже в этом разделе");
            return;
        }
        if (slot.parentId() != null) menuClosed.remove(slot.parentId());
        changed();
        Notification.show("Добавлено: разделов " + added.sections() + ", пунктов " + added.entries());
    }

    // ------------------------------------------------------------------ drag and drop

    void onDrop(MenuRow target, GridDropLocation location) {
        Object source = dragged;
        dragged = null;
        if (draft == null || source == null) return;
        try {
            if (source instanceof MenuPaletteModel.FunctionNode node) {
                var slot = entrySlot(target, location, null);
                if (slot == null) {
                    Notification.show("Перетащите пункт на раздел или между пунктами");
                    return;
                }
                addFunction(node.function(), slot);
            } else if (source instanceof MenuPaletteModel.AreaNode area) {
                addArea(area, sectionSlot(target, location, null));
            } else if (source instanceof MenuRow row && exists(row)) {
                moveRow(row, target, location);
            }
        } catch (IllegalArgumentException invalid) {
            Notification.show(invalid.getMessage());
        }
    }

    private void moveRow(MenuRow row, MenuRow target, GridDropLocation location) {
        if (row.equals(target)) return;
        if (row.section()) {
            var slot = sectionSlot(target, location, row.id());
            if (!draft.moveSection(row.id(), slot.parentId(), slot.index())) {
                Notification.show("Раздел нельзя перенести внутрь самого себя");
                return;
            }
        } else {
            var slot = entrySlot(target, location, row.id());
            if (slot == null) return;
            draft.moveEntry(row.id(), slot.parentId(), slot.index());
        }
        changed();
        menuGrid.select(row);
    }

    /** Where an entry dropped on the target goes; {@code null} if there is no place. The moved id is left out of the order. */
    private Slot entrySlot(MenuRow target, GridDropLocation location, String movedId) {
        if (target == null) return null;
        if (target.section()) {
            // Below an open section means "first inside it"; anywhere else on the row means "inside, at the end".
            boolean first = location == GridDropLocation.BELOW && !menuClosed.contains(target.id());
            return new Slot(target.id(), first ? 0 : -1);
        }
        var entry = draft.entry(target.id()).orElse(null);
        if (entry == null) return null;
        var siblings = new ArrayList<>(draft.entries(entry.sectionId()));
        siblings.removeIf(candidate -> candidate.id().equals(movedId));
        int at = siblings.indexOf(entry);
        if (at < 0) return new Slot(entry.sectionId(), -1);
        return new Slot(entry.sectionId(), location == GridDropLocation.ABOVE ? at : at + 1);
    }

    private Slot sectionSlot(MenuRow target, GridDropLocation location, String movedId) {
        if (target == null) return new Slot(null, -1);
        if (!target.section()) {
            return new Slot(draft.entry(target.id()).map(NavigationStructure.Entry::sectionId).orElse(null), -1);
        }
        var section = draft.section(target.id()).orElse(null);
        if (section == null) return new Slot(null, -1);
        if (location == GridDropLocation.ON_TOP) return new Slot(section.id(), -1);
        if (location == GridDropLocation.BELOW && !menuClosed.contains(section.id())
                && (!draft.childSections(section.id()).isEmpty() || !draft.entries(section.id()).isEmpty())) {
            return new Slot(section.id(), 0);
        }
        var siblings = new ArrayList<>(draft.childSections(section.parentId()));
        siblings.removeIf(candidate -> candidate.id().equals(movedId));
        int at = siblings.indexOf(section);
        if (at < 0) return new Slot(section.parentId(), -1);
        return new Slot(section.parentId(), location == GridDropLocation.ABOVE ? at : at + 1);
    }

    // ------------------------------------------------------------------ access for tests

    NavigationMenu currentMenu() { return current; }
    MenuDraft draft() { return draft; }
    MenuPaletteModel palette() { return palette; }
    TreeGrid<MenuRow> menuGrid() { return menuGrid; }
    TreeGrid<MenuPaletteModel.Node> paletteGrid() { return paletteGrid; }
    Button saveButton() { return saveButton; }
    Button copyButton() { return copyButton; }
    Button previewButton() { return previewButton; }
    Button menuButton() { return menuButton; }
    TextField searchField() { return search; }
    boolean bannerVisible() { return banner.isVisible(); }
    boolean stripVisible() { return strip.isVisible(); }
    String stripText() { return strip.getElement().getTextRecursively(); }
    boolean locked() { return lock.isVisible(); }
    String lockText() { return lock.getText(); }
    void select(MenuRow row) { menuGrid.select(row); }
    void drag(Object source) { dragged = source; }
    LazyContextMenu chooserMenu() { return chooserMenu; }
    LazyContextMenu moreMenu() { return moreMenu; }
    void commitEdit(MenuRow row, String text) { editing = row; finishEdit(row, text); }
    boolean editing(MenuRow row) { return row.equals(editing); }
    void moveRowBy(MenuRow row, int delta) { moveBy(row, delta); }
    void useMenu(NavigationMenu menu) { open(menu); }
    void createCopy(String name) {
        afterCreated(service.copy(current.id(), name));
    }
    void createEmptyMenu(String name) { afterCreated(service.createEmpty(name)); }
}
