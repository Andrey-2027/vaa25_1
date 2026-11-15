package org.ip.views.admin.menu;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.ipro.navigation.NavigationMenu;
import org.ipro.navigation.NavigationMenuService;
import org.ipro.navigation.NavigationUserDirectory.UserOption;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * "Кому назначено": users of a named menu. A user has one assigned menu, so a check here replaces
 * the previous one; the row of such a user says which menu he has now. "Готово" saves the assignment at once,
 * separately from the menu structure.
 */
final class MenuAssignmentDialog extends Dialog {
    private static final int LOOKUP_LIMIT = 50;

    private final NavigationMenuService service;
    private final NavigationMenu menu;
    private final Map<Long, String> menuNames;
    private final Function<String, String> userLabel;
    private final Runnable onDone;
    private final Set<String> original;
    private final Set<String> selected;
    private final Map<String, String> labels = new LinkedHashMap<>();
    private final Map<String, String> currentMenuOf = new LinkedHashMap<>();
    private final TextField search = new TextField();
    private final Div list = new Div();

    MenuAssignmentDialog(NavigationMenuService service, NavigationMenu menu, Map<Long, String> menuNames,
                         Function<String, String> userLabel, Runnable onDone) {
        this.service = service;
        this.menu = menu;
        this.menuNames = menuNames;
        this.userLabel = userLabel;
        this.onDone = onDone;
        this.original = new LinkedHashSet<>(service.assignees(menu.id()));
        this.selected = new LinkedHashSet<>(original);

        addClassName("menu-assign-dialog");
        setHeaderTitle("«" + menu.name() + "» — пользователи");
        setWidth("min(440px, 96vw)");

        search.setPlaceholder("Найти пользователя");
        search.setAriaLabel("Найти пользователя");
        search.setPrefixComponent(VaadinIcon.SEARCH.create());
        search.setClearButtonVisible(true);
        search.setWidthFull();
        search.addThemeName("small");
        search.setValueChangeMode(ValueChangeMode.LAZY);
        search.setValueChangeTimeout(250);
        search.addValueChangeListener(event -> render());

        list.addClassName("menu-assign-list");
        Span hint = new Span("У пользователя одно назначенное меню: отметка здесь заменит прежнее.");
        hint.addClassName("me-hint");

        var body = new Div(search, list, hint);
        body.addClassName("menu-assign-body");
        add(body);

        Button cancel = new Button("Отмена", event -> close());
        Button done = new Button("Готово", event -> apply());
        done.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        getFooter().add(cancel, done);
        render();
    }

    /** The users that are shown: those of this menu first, then the search result. */
    private void render() {
        var shown = new LinkedHashMap<String, String>();
        String term = search.getValue() == null ? "" : search.getValue().strip();
        List<UserOption> found = List.of();
        try {
            found = service.lookupUsers(term, LOOKUP_LIMIT);
            found.forEach(option -> labels.put(option.key(), option.title()));
        } catch (RuntimeException failure) {
            Notification.show("Не удалось найти пользователей: " + failure.getMessage());
        }
        // Rows do not jump when a box is checked: first those assigned here, then the rest in the directory order.
        if (term.isEmpty()) {
            for (String key : original) shown.put(key, labelOf(key));
        }
        for (UserOption option : found) shown.putIfAbsent(option.key(), option.title());
        if (term.isEmpty()) {
            for (String key : selected) shown.putIfAbsent(key, labelOf(key));
        }
        list.removeAll();
        if (shown.isEmpty()) {
            Span none = new Span("Никого не найдено");
            none.addClassName("me-hint");
            list.add(none);
            return;
        }
        shown.forEach((key, title) -> list.add(row(key, title)));
    }

    private Div row(String key, String title) {
        Checkbox box = new Checkbox(selected.contains(key));
        box.setAriaLabel(title);
        box.addValueChangeListener(event -> {
            if (event.getValue()) selected.add(key);
            else selected.remove(key);
            render();
        });
        Span name = new Span(title);
        name.addClassName("me-title");
        var row = new Div(box, name);
        row.addClassName("me-row");
        String other = selected.contains(key) ? otherMenu(key) : null;
        if (other != null) {
            Span tag = new Span("сейчас: " + other);
            tag.addClassNames("me-tag", "me-tag-warn");
            row.add(tag);
        }
        return row;
    }

    /** The menu a user has now, if it is not this one. */
    private String otherMenu(String key) {
        String name = currentMenuOf.computeIfAbsent(key, user -> service.assignmentOf(user)
            .filter(id -> id != menu.id()).map(id -> menuNames.getOrDefault(id, "№" + id)).orElse(""));
        return name.isEmpty() ? null : name;
    }

    private String labelOf(String key) {
        return labels.computeIfAbsent(key, userLabel);
    }

    private void apply() {
        var add = new LinkedHashSet<>(selected);
        add.removeAll(original);
        var remove = new LinkedHashSet<>(original);
        remove.removeAll(selected);
        try {
            if (!add.isEmpty() || !remove.isEmpty()) service.assign(menu.id(), add, remove);
        } catch (RuntimeException failure) {
            Notification.show("Не удалось сохранить назначение: " + failure.getMessage());
            return;
        }
        close();
        onDone.run();
    }

    List<String> shownUsers() {
        return list.getChildren().map(row -> row.getChildren().skip(1).findFirst().orElse(null))
            .filter(Span.class::isInstance).map(component -> ((Span) component).getText()).toList();
    }
}
