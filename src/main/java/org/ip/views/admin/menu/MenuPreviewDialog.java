package org.ip.views.admin.menu;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import org.ip.views.workspace.SubsystemPage;
import org.ipro.navigation.NavigationCatalog;
import org.ipro.navigation.NavigationFunction;
import org.ip.navigation.ApplicationNavigationCommands;
import org.ipro.navigation.NavigationFunctions;
import org.ipro.navigation.NavigationImportance;
import org.ipro.navigation.NavigationMenu;
import org.ipro.navigation.NavigationMenuService;
import org.ipro.navigation.NavigationResolver;
import org.ipro.navigation.NavigationTree;
import org.ipro.navigation.NavigationUserDirectory.UserOption;
import org.ipro.rls.RlsReadGate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * "Как увидит пользователь": which menu applies to the chosen user and why, and what his left panel and
 * section pages hold. The menu is read from the store, so unsaved edits are not in it (a warning says so).
 * A list is shown if the user may read the type; a command is shown without an availability check, because
 * the decision is made for the signed-in user only, and is marked so.
 */
final class MenuPreviewDialog extends Dialog {
    private static final String CHOOSE = "Выберите пользователя, чтобы увидеть его меню.";

    private final NavigationMenuService service;
    private final RlsReadGate readGate;
    private final ApplicationNavigationCommands commands;
    private final NavigationResolver resolver = new NavigationResolver();
    private final ComboBox<UserOption> user = new ComboBox<>();
    private final Span reason = new Span();
    private final Div navigation = new Div();
    private final Div page = new Div();
    private NavigationTree tree;
    private String openSection;

    MenuPreviewDialog(NavigationMenuService service, RlsReadGate readGate,
                      ApplicationNavigationCommands commands, boolean unsavedEdits) {
        this.service = service;
        this.readGate = readGate;
        this.commands = commands;

        addClassName("menu-preview-dialog");
        setHeaderTitle("Как увидит пользователь");
        setWidth("min(760px, 96vw)");
        setHeight("min(520px, 92vh)");

        user.setPlaceholder("Выберите пользователя");
        user.setAriaLabel("Пользователь");
        user.setItemLabelGenerator(UserOption::title);
        user.setClearButtonVisible(true);
        user.setWidth("20em");
        user.addThemeName("small");
        user.setItems(query -> {
            if (query.getOffset() > 0) return Stream.<UserOption>empty();
            return service.lookupUsers(query.getFilter().orElse(""), Math.min(50, Math.max(1, query.getLimit()))).stream();
        });
        user.addValueChangeListener(event -> show(event.getValue()));

        reason.addClassName("me-info");
        var head = new Div(user, reason);
        head.addClassName("menu-preview-head");

        navigation.addClassName("menu-preview-nav");
        page.addClassName("menu-preview-page");
        var body = new Div(navigation, page);
        body.addClassName("menu-preview-body");

        Span note = new Span("Пользователь видит пункт, если есть права на чтение. Команды (молния) показаны без проверки"
            + " доступности. Пустые разделы скрываются.");
        note.addClassName("me-hint");

        var content = new Div();
        content.addClassName("menu-preview-content");
        if (unsavedEdits) {
            Span warning = new Span("Есть несохранённые правки: предпросмотр построен из сохранённого меню.");
            warning.addClassNames("me-tag", "me-tag-warn", "menu-preview-warning");
            content.add(warning);
        }
        content.add(head, body, note);
        add(content);
        getFooter().add(new Button("Закрыть", event -> close()));
        showNothing(CHOOSE);
    }

    void show(UserOption option) {
        if (option == null) {
            showNothing(CHOOSE);
            return;
        }
        try {
            Map<Long, NavigationMenu> menus = service.menus().stream()
                .collect(Collectors.toMap(NavigationMenu::id, Function.identity()));
            Optional<Long> assigned = service.assignmentOf(option.key()).filter(menus::containsKey);
            NavigationMenu applied = assigned.map(menus::get)
                .orElseGet(() -> menus.values().stream().filter(NavigationMenu::main).findFirst().orElseThrow());
            reason.setText("Действует «" + applied.name() + "»: "
                + (assigned.isPresent() ? "назначено лично." : "назначения нет, действует основное."));

            var structure = service.structure(applied.id());
            Map<String, NavigationFunction> functions = service.functions().stream().map(found -> found.function())
                .collect(Collectors.toMap(NavigationFunction::key, Function.identity(), (a, b) -> a));
            var catalog = NavigationCatalog.build(structure, new NavigationFunctions() {
                @Override public List<NavigationFunction> all() { return List.copyOf(functions.values()); }
                @Override public Optional<NavigationFunction> find(String key) { return Optional.ofNullable(functions.get(key)); }
            });
            tree = resolver.resolve(catalog, item -> switch (item.function().kind()) {
                case QUERY_LIST -> commands.canPreviewQueryList(item.function(), option.title());
                case CREATE, ACTION -> true;
                case LIST, LIST_VARIANT -> readGate.canRead(item.entityClass(), option.title());
            });
            openSection = tree.roots().isEmpty() ? null : tree.roots().getFirst().id();
            renderNavigation();
            renderPage();
        } catch (RuntimeException failure) {
            Notification.show("Не удалось построить предпросмотр: " + failure.getMessage());
            showNothing("Предпросмотр недоступен.");
        }
    }

    private void showNothing(String text) {
        tree = null;
        openSection = null;
        reason.setText("");
        navigation.removeAll();
        page.removeAll();
        Span empty = new Span(text);
        empty.addClassName("me-hint");
        page.add(empty);
    }

    private void renderNavigation() {
        navigation.removeAll();
        Span home = new Span("Главное");
        home.addClassNames("menu-preview-nav-item", "menu-preview-nav-home");
        navigation.add(home);
        for (NavigationTree.Node node : tree.roots()) {
            var item = new Div();
            item.addClassName("menu-preview-nav-item");
            if (node.id().equals(openSection)) item.addClassName("menu-preview-nav-current");
            item.add(icon(node.icon()), new Span(node.title()));
            item.addClickListener(event -> {
                openSection = node.id();
                renderNavigation();
                renderPage();
            });
            navigation.add(item);
        }
    }

    private void renderPage() {
        page.removeAll();
        if (tree == null) return;
        Optional<NavigationTree.Node> node = openSection == null ? Optional.empty() : tree.findSubsystem(openSection);
        if (node.isEmpty()) {
            Span empty = new Span("Меню этого пользователя пусто: у него нет прав на пункты.");
            empty.addClassName("me-hint");
            page.add(empty);
            return;
        }
        for (var group : SubsystemPage.from(node.get()).groups()) {
            var block = new Div();
            block.addClassName("subsystem-home-group");
            if (!group.title().isEmpty()) {
                Span title = new Span(group.title());
                title.addClassName("menu-preview-group-title");
                block.add(title);
            }
            for (var link : group.links()) {
                var line = new Div();
                line.addClassName("menu-preview-link");
                if (link.importance() == NavigationImportance.IMPORTANT) line.addClassName("menu-preview-link-important");
                if (group.key().equals(SubsystemPage.SEE_ALSO_KEY)) line.addClassName("menu-preview-link-secondary");
                if (link.function().command()) {
                    line.add(VaadinIcon.BOLT.create(), new Span(link.title()));
                    Span unchecked = new Span("доступность не проверена");
                    unchecked.addClassName("me-hint");
                    line.add(unchecked);
                } else {
                    line.add(new Span(link.title()));
                }
                block.add(line);
            }
            page.add(block);
        }
    }

    private static Icon icon(String name) {
        try {
            return VaadinIcon.valueOf(name).create();
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return VaadinIcon.FOLDER_O.create();
        }
    }

    /** Titles of the sections in the preview's left panel. */
    List<String> sectionTitles() {
        return tree == null ? List.of() : tree.roots().stream().map(NavigationTree.Node::title).toList();
    }

    String reasonText() { return reason.getText(); }

    /** Texts of the links of the opened section page. */
    List<String> pageLinks() {
        return page.getChildren().flatMap(Component::getChildren)
            .filter(component -> component.getElement().getClassList().contains("menu-preview-link"))
            .map(line -> line.getChildren().filter(Span.class::isInstance).map(Span.class::cast)
                .findFirst().map(Span::getText).orElse("")).toList();
    }
}
