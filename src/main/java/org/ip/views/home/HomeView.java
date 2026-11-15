package org.ip.views.home;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.dnd.DragSource;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.ip.navigation.ApplicationNavigationCommands;
import org.ip.views.admin.menu.LazyContextMenu;
import org.ipro.filtergrid.inmemory.InMemoryFilterGrid;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.form.spi.WorkspaceGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Scope;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * «Главное»: закреплённое пользователем, разложенное по группам. Один уровень групп колонками; пункт
 * открывается как обычно (список — во вкладке, карточка — по настройке открытия). Справа — сворачиваемая
 * панель «Недавнее»: пока она свёрнута, записи не читаются. Все правки сохраняются сразу.
 */
@SpringComponent
@Scope("prototype")
public class HomeView extends Div {
    private static final Logger log = LoggerFactory.getLogger(HomeView.class);
    public static final String ENTRY_ID = "home";
    private static final int SUGGESTIONS = 8;

    private final HomeService service;
    private final HomeResolver resolver;
    private final HomeGroupResolver groupResolver;
    private final FormNavigator navigator;
    private final ApplicationNavigationCommands commands;
    private final Clock clock;

    private final Div main = new Div();
    private final Div recent = new Div();
    private HomeLayout layout = new HomeLayout();
    private HomeResolver.Page page = new HomeResolver.Page(List.of(), List.of());
    private boolean recentExpanded;
    private String editing;
    private String dragging;
    private List<HomeResolver.RecentRow> recentRows = List.of();
    private List<RecentRow> gridRows = List.of();
    private InMemoryFilterGrid<RecentRow> recentGrid;

    /** Строка таблицы «Недавнее». */
    record RecentRow(HomeResolver.Resolved resolved, Instant at, String when) {}

    @org.springframework.beans.factory.annotation.Autowired
    public HomeView(HomeService service, HomeResolver resolver, HomeGroupResolver groupResolver,
                    FormNavigator navigator, ApplicationNavigationCommands commands,
                    ObjectProvider<WorkspaceGateway> workspaces) {
        this(service, resolver, groupResolver, navigator, commands, Clock.systemUTC());
        var workspace = workspaces == null ? null : workspaces.getIfAvailable();
        if (workspace != null) {
            // Закрепление делается на других страницах: при возврате на вкладку «Главное» перечитываем.
            workspace.addActiveEntryListener(entryId -> {
                if (ENTRY_ID.equals(entryId) && isAttached()) refresh();
            });
        }
    }

    HomeView(HomeService service, HomeResolver resolver, HomeGroupResolver groupResolver,
             FormNavigator navigator, ApplicationNavigationCommands commands, Clock clock) {
        this.service = service;
        this.resolver = resolver;
        this.groupResolver = groupResolver;
        this.navigator = navigator;
        this.commands = commands;
        this.clock = clock;

        addClassName("home-view");
        main.addClassName("home-main");
        recent.addClassName("home-recent");
        add(main, recent);
        try {
            recentExpanded = service.ui().recentExpanded();
        } catch (RuntimeException failure) {
            log.warn("Не удалось прочитать состояние «Главного»", failure);
        }
        refresh();
    }

    /** Перечитывает раскладку и (если панель раскрыта) «Недавнее». */
    public void refresh() {
        if (editing != null) return;
        try {
            layout = service.layout();
            page = resolver.resolve(layout);
            renderMain();
        } catch (RuntimeException failure) {
            log.error("Не удалось загрузить «Главное»", failure);
            main.removeAll();
            Span error = new Span("Не удалось загрузить «Главное». Обновите страницу или обратитесь к администратору.");
            error.addClassName("home-hint");
            main.add(error);
        }
        renderRecent();
    }

    // ------------------------------------------------------------------ main

    private void renderMain() {
        main.removeAll();
        if (layout.isEmpty()) {
            main.add(emptyState());
            return;
        }
        var groups = new Div();
        groups.addClassName("home-groups");
        for (var group : page.groups()) groups.add(groupCard(group));
        main.add(groups);
        if (!page.hidden().isEmpty()) {
            var hidden = new Div();
            hidden.addClassNames("home-hidden", "home-hint");
            hidden.add(VaadinIcon.EYE_SLASH.create(), new Span("Скрыто (нет доступа): " + page.hidden().size() + " — "));
            Button remove = new Button("убрать", event -> removeHidden());
            remove.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY_INLINE);
            hidden.add(remove);
            main.add(hidden);
        }
    }

    private Component emptyState() {
        var empty = new Div();
        empty.addClassName("home-empty");
        Span title = new Span("Здесь будет ваше избранное");
        title.addClassName("home-empty-title");
        Span hint = new Span("Закрепляйте звёздочкой на странице раздела или из «Недавнего» — пункты сами разложатся"
            + " по разделам вашего меню.");
        hint.addClassName("home-hint");
        empty.add(title, hint);
        List<HomeResolver.Resolved> suggestions = List.of();
        try {
            suggestions = resolver.suggestions(SUGGESTIONS);
        } catch (RuntimeException failure) {
            log.warn("Не удалось получить «Важное из вашего меню»", failure);
        }
        if (!suggestions.isEmpty()) {
            Span heading = new Span("Важное из вашего меню");
            heading.addClassName("home-suggest-title");
            empty.add(heading);
            for (var suggestion : suggestions) {
                var row = new Div();
                row.addClassNames("home-item", "home-suggestion");
                Span name = new Span(suggestion.title());
                name.addClassName("home-link");
                name.addClickListener(event -> open(suggestion));
                Button pin = new Button("закрепить", VaadinIcon.STAR_O.create(), event -> togglePin(suggestion.pin()));
                pin.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY_INLINE);
                row.add(typeIcon(suggestion), name, pin);
                empty.add(row);
            }
        }
        return empty;
    }

    private Component groupCard(HomeResolver.ShownGroup shown) {
        var group = shown.group();
        var card = new Div();
        card.addClassName("home-group");
        card.getElement().setAttribute("data-group", group.id());

        var head = new Div();
        head.addClassName("home-group-head");
        if (group.id().equals(editing)) {
            head.add(editor(group.title(), text -> {
                try {
                    layout.renameGroup(group.id(), text);
                    save();
                } catch (IllegalArgumentException invalid) {
                    Notification.show(invalid.getMessage());
                }
            }));
        } else {
            Span title = new Span(group.title());
            title.addClassName("home-group-title");
            title.addClickListener(event -> {
                if (event.getClickCount() == 2) startEditing(group.id());
            });
            head.add(title);
            head.add(dots(menu -> fillGroupMenu(menu, group)));
        }
        card.add(head);

        var items = new Div();
        items.addClassName("home-items");
        for (var item : shown.items()) items.add(itemRow(group, item));
        if (shown.items().isEmpty()) {
            Span none = new Span("Пусто: закрепите пункт или перетащите сюда");
            none.addClassName("home-hint");
            items.add(none);
        }
        card.add(items);

        DropTarget.create(card).addDropListener(event -> dropOnGroup(group.id()));
        return card;
    }

    private Component itemRow(HomeLayout.Group group, HomeResolver.ShownItem shown) {
        var item = shown.item();
        var row = new Div();
        row.addClassName("home-item");
        row.getElement().setAttribute("data-item", item.id());
        if (item.id().equals(editing)) {
            row.add(typeIcon(shown.resolved()));
            row.add(editor(shown.title(), text -> {
                try {
                    layout.renameItem(item.id(), text);
                    save();
                } catch (IllegalArgumentException invalid) {
                    Notification.show(invalid.getMessage());
                }
            }));
            return row;
        }
        Icon grip = VaadinIcon.ELLIPSIS_DOTS_V.create();
        grip.addClassName("home-grip");
        Span name = new Span(shown.title());
        name.addClassName("home-link");
        name.addClickListener(event -> open(shown.resolved()));
        row.add(grip, typeIcon(shown.resolved()), name, dots(menu -> fillItemMenu(menu, group, item)));

        DragSource<Div> source = DragSource.create(row);
        source.addDragStartListener(event -> dragging = item.id());
        source.addDragEndListener(event -> dragging = null);
        DropTarget.create(row).addDropListener(event -> dropOnItem(item.id()));
        return row;
    }

    private static Icon typeIcon(HomeResolver.Resolved resolved) {
        Icon icon = (resolved.isRecord() ? VaadinIcon.FILE_TEXT_O : resolved.isCommand() ? VaadinIcon.BOLT
            : VaadinIcon.LIST).create();
        icon.addClassName("home-type");
        return icon;
    }

    private static Button dots(java.util.function.Consumer<ContextMenu> filler) {
        var button = new Button(VaadinIcon.ELLIPSIS_DOTS_H.create());
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON);
        button.addClassName("home-dots");
        button.setAriaLabel("Действия");
        new LazyContextMenu(button, menu -> {
            filler.accept(menu);
            return true;
        });
        return button;
    }

    private Component editor(String initial, java.util.function.Consumer<String> commit) {
        var field = new TextField();
        field.setValue(initial);
        field.setMaxLength(HomeLayout.MAX_TITLE);
        field.setWidthFull();
        field.addThemeName("small");
        field.setAriaLabel("Название");
        field.addClassName("home-editor");
        boolean[] done = {false};
        Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            editing = null;
            commit.accept(field.getValue());
        };
        field.addKeyDownListener(Key.ENTER, event -> finish.run());
        field.addKeyDownListener(Key.ESCAPE, event -> {
            if (done[0]) return;
            done[0] = true;
            editing = null;
            refresh();
        });
        field.addBlurListener(event -> finish.run());
        field.addAttachListener(event -> field.focus());
        return field;
    }

    void startEditing(String id) {
        editing = id;
        renderMain();
    }

    // ------------------------------------------------------------------ menus

    private void fillGroupMenu(ContextMenu menu, HomeLayout.Group group) {
        menu.addItem("Переименовать", event -> startEditing(group.id()));
        var groups = layout.groups();
        int index = groups.indexOf(layout.group(group.id()).orElse(group));
        var up = menu.addItem("Выше", event -> {
            layout.moveGroup(group.id(), index - 1);
            save();
        });
        up.setEnabled(index > 0);
        var down = menu.addItem("Ниже", event -> {
            layout.moveGroup(group.id(), index + 1);
            save();
        });
        down.setEnabled(index >= 0 && index < groups.size() - 1);
        menu.addSeparator();
        menu.addItem("Удалить…", event -> askRemoveGroup(group));
    }

    private void fillItemMenu(ContextMenu menu, HomeLayout.Group group, HomeLayout.Item item) {
        menu.addItem("Переименовать", event -> startEditing(item.id()));
        var current = layout.group(group.id()).orElse(group);
        int index = current.items().stream().map(HomeLayout.Item::id).toList().indexOf(item.id());
        menu.addItem("Выше", event -> {
            layout.moveItem(item.id(), group.id(), index - 1);
            save();
        }).setEnabled(index > 0);
        menu.addItem("Ниже", event -> {
            layout.moveItem(item.id(), group.id(), index + 1);
            save();
        }).setEnabled(index >= 0 && index < current.items().size() - 1);
        var move = menu.addItem("Переместить в группу");
        for (var other : layout.groups()) {
            if (other.id().equals(group.id())) continue;
            move.getSubMenu().addItem(other.title(), event -> {
                layout.moveItem(item.id(), other.id(), -1);
                save();
            });
        }
        move.getSubMenu().addItem("Новая группа…", event -> askNewGroup(item.id()));
        menu.addSeparator();
        menu.addItem("Открепить", event -> {
            layout.removeItems(List.of(item.id()));
            save();
        });
    }

    private void askRemoveGroup(HomeLayout.Group group) {
        if (group.items().isEmpty()) {
            layout.removeGroup(group.id());
            save();
            return;
        }
        var dialog = new ConfirmDialog();
        dialog.setHeader("Удалить группу «" + group.title() + "»?");
        dialog.setText("Закреплённые в ней пункты (" + group.items().size() + ") будут откреплены.");
        dialog.setConfirmText("Удалить");
        dialog.setConfirmButtonTheme("error primary");
        dialog.setCancelable(true);
        dialog.setCancelText("Отмена");
        dialog.addConfirmListener(event -> {
            layout.removeGroup(group.id());
            save();
        });
        dialog.open();
    }

    private void askNewGroup(String itemId) {
        var dialog = new Dialog();
        dialog.setHeaderTitle("Новая группа");
        dialog.setWidth("min(360px, 96vw)");
        var field = new TextField("Название");
        field.setMaxLength(HomeLayout.MAX_TITLE);
        field.setWidthFull();
        dialog.add(field);
        Runnable submit = () -> {
            try {
                layout.moveItemToNewGroup(itemId, field.getValue());
                dialog.close();
                save();
            } catch (IllegalArgumentException invalid) {
                field.setInvalid(true);
                field.setErrorMessage(invalid.getMessage());
            }
        };
        field.addKeyDownListener(Key.ENTER, event -> submit.run());
        Button ok = new Button("Создать", event -> submit.run());
        ok.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Отмена", event -> dialog.close()), ok);
        dialog.addOpenedChangeListener(event -> {
            if (event.isOpened()) field.focus();
        });
        dialog.open();
    }

    // ------------------------------------------------------------------ actions

    /** Открывает как обычно: список и команды — как из меню, запись — карточкой по настройке открытия. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void open(HomeResolver.Resolved resolved) {
        try {
            if (resolved.isRecord()) {
                navigator.openItemForm((Class) resolved.type(), null, resolved.id(), null, null);
                return;
            }
            var decision = commands.execute(resolved.function(), navigator);
            if (!decision.actionable()) {
                Notification.show(decision.message() == null || decision.message().isBlank()
                    ? "Действие недоступно" : decision.message());
            }
        } catch (RuntimeException failure) {
            log.error("Не удалось открыть закреплённое", failure);
            Notification.show("Не удалось открыть: " + failure.getMessage());
        }
    }

    /** Закрепляет или открепляет; новая группа называется по разделу меню пользователя. */
    void togglePin(HomePin pin) {
        try {
            var fresh = service.layout();
            if (!fresh.unpin(pin)) fresh.pin(pin, groupResolver.groupTitle(pin));
            service.saveLayout(fresh);
        } catch (RuntimeException failure) {
            log.error("Не удалось изменить закреплённое", failure);
            Notification.show("Не удалось сохранить: " + failure.getMessage());
        }
        refresh();
    }

    private void removeHidden() {
        layout.removeItems(page.hidden().stream().map(HomeLayout.Item::id).toList());
        save();
    }

    private void dropOnItem(String targetId) {
        String moved = dragging;
        dragging = null;
        if (moved == null || moved.equals(targetId)) return;
        var group = layout.groupOf(targetId);
        if (group.isEmpty() || layout.item(moved).isEmpty()) return;
        var siblings = new ArrayList<>(group.get().items().stream().map(HomeLayout.Item::id).toList());
        siblings.remove(moved);
        layout.moveItem(moved, group.get().id(), siblings.indexOf(targetId));
        save();
    }

    private void dropOnGroup(String groupId) {
        String moved = dragging;
        dragging = null;
        if (moved == null || layout.item(moved).isEmpty()) return;
        layout.moveItem(moved, groupId, -1);
        save();
    }

    /** Сохраняет раскладку целиком и перерисовывает страницу. */
    private void save() {
        try {
            service.saveLayout(layout);
        } catch (RuntimeException failure) {
            log.error("Не удалось сохранить «Главное»", failure);
            Notification.show("Не удалось сохранить: " + failure.getMessage());
        }
        refresh();
    }

    // ------------------------------------------------------------------ recent

    private void renderRecent() {
        recent.removeAll();
        recent.removeClassName("home-recent-open");
        recentGrid = null;
        if (!recentExpanded) {
            // Свёрнуто: записи не читаются.
            var strip = new Button(VaadinIcon.TIME_BACKWARD.create(), event -> setRecentExpanded(true));
            strip.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            strip.addClassName("home-recent-strip");
            strip.setAriaLabel("Показать «Недавнее»");
            strip.setTooltipText("Недавнее");
            Span label = new Span("Недавнее");
            label.addClassName("home-recent-label");
            label.addClickListener(event -> setRecentExpanded(true));
            recent.add(strip, label);
            return;
        }
        recent.addClassName("home-recent-open");
        var head = new Div();
        head.addClassName("home-recent-head");
        Span title = new Span("Недавнее");
        title.addClassName("home-recent-title");
        Span limit = new Span(HomeJson.MAX_RECENT + " последних");
        limit.addClassName("home-hint");
        Button collapse = new Button(VaadinIcon.CHEVRON_RIGHT_SMALL.create(), event -> setRecentExpanded(false));
        collapse.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON);
        collapse.setAriaLabel("Свернуть «Недавнее»");
        head.add(VaadinIcon.TIME_BACKWARD.create(), title, limit, collapse);
        recent.add(head);

        try {
            recentRows = resolver.recent(service.recent());
        } catch (RuntimeException failure) {
            log.warn("Не удалось прочитать «Недавнее»", failure);
            recentRows = List.of();
        }
        Instant now = clock.instant();
        var rows = recentRows.stream()
            .map(row -> new RecentRow(row.resolved(), row.at(), RecentTime.format(row.at(), now, ZoneId.systemDefault())))
            .toList();
        gridRows = rows;
        recentGrid = new InMemoryFilterGrid<>(RecentRow.class, rows);
        recentGrid.addColumn("type", "Тип", row -> row.resolved().typeTitle()).setResizable(true).setFlexGrow(1);
        recentGrid.addColumn("caption", "Представление", row -> row.resolved().caption()).setResizable(true).setFlexGrow(2);
        recentGrid.addColumn("when", "Открыто", RecentRow::when).setResizable(true).setWidth("8.5em").setFlexGrow(0)
            .setComparator(RecentRow::at);
        recentGrid.addComponentColumn("pin", "", this::pinButton).setWidth("3em").setFlexGrow(0);
        recentGrid.setWidthFull();
        recentGrid.setCompact(true);
        recentGrid.addClassName("home-recent-grid");
        recent.add(recentGrid);
        if (rows.isEmpty()) {
            Span none = new Span("Пока ничего не открывали.");
            none.addClassName("home-hint");
            recent.add(none);
        }
    }

    private Component pinButton(RecentRow row) {
        boolean pinned = layout.isPinned(row.resolved().pin());
        var button = new Button((pinned ? VaadinIcon.STAR : VaadinIcon.STAR_O).create(),
            event -> togglePin(row.resolved().pin()));
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON);
        button.addClassName(pinned ? "home-star-on" : "home-star");
        button.setAriaLabel(pinned ? "Открепить" : "Закрепить");
        button.setTooltipText(pinned ? "Открепить" : "Закрепить");
        return button;
    }

    private void setRecentExpanded(boolean expanded) {
        recentExpanded = expanded;
        service.saveUi(new HomeJson.UiState(expanded));
        renderRecent();
    }

    // ------------------------------------------------------------------ for tests

    HomeLayout layout() { return layout; }
    HomeResolver.Page page() { return page; }
    boolean recentExpanded() { return recentExpanded; }
    InMemoryFilterGrid<RecentRow> recentGrid() { return recentGrid; }
    List<RecentRow> recentRows() { return gridRows; }
    void drag(String itemId) { dragging = itemId; }
    void dropOnItemForTest(String targetId) { dropOnItem(targetId); }
    void dropOnGroupForTest(String groupId) { dropOnGroup(groupId); }
    void toggleRecent() { setRecentExpanded(!recentExpanded); }
    void pin(HomePin pin) { togglePin(pin); }
}
