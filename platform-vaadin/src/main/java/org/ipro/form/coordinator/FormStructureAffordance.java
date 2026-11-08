package org.ipro.form.coordinator;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.contextmenu.SubMenu;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarVariant;
import com.vaadin.flow.component.notification.Notification;
import org.ipro.form.action.CopyLinkButton;
import org.ipro.form.link.ApplicationBasePath;
import org.ipro.form.link.EntityStructureNavigation;

import java.util.Optional;
import java.util.function.Consumer;

/** Одна точка меню разработки для списков и обоих host'ов ItemForm. */
final class FormStructureAffordance {
    private static final String MENU_ID = "entity-structure-menu";

    private FormStructureAffordance() {
    }

    static MenuBar attach(Component toolbar, Class<?> type, EntityStructureNavigation navigation,
                           boolean dialog) {
        return attach(toolbar, type, navigation, dialog,
            reason -> Notification.show(reason, 4000, Notification.Position.MIDDLE),
            path -> UI.getCurrent().getPage().executeJs("window.open($0, '_blank', 'noopener');",
                ApplicationBasePath.current() + path), CopyLinkButton::copyAddress);
    }

    static MenuBar attach(Component toolbar, Class<?> type, EntityStructureNavigation navigation,
                           boolean dialog, Consumer<String> refused, Consumer<String> browser,
                           Consumer<String> copy) {
        MenuBar previous = (MenuBar) ComponentUtil.getData(toolbar, FormStructureAffordance.class.getName());
        if (previous != null) {
            ((HasComponents) toolbar).remove(previous);
            ComponentUtil.setData(toolbar, FormStructureAffordance.class.getName(), null);
        }
        if (navigation == null || !navigation.availability(type).available()) {
            return null;
        }
        MenuBar menu = new MenuBar();
        menu.setId(MENU_ID);
        menu.addThemeVariants(MenuBarVariant.LUMO_TERTIARY, MenuBarVariant.LUMO_SMALL);
        MenuItem developmentItem = menu.addItem("Разработка");
        populate(developmentItem.getSubMenu(), type, navigation, dialog, refused, browser, copy);
        ((HasComponents) toolbar).add(menu);
        ComponentUtil.setData(toolbar, FormStructureAffordance.class.getName(), menu);
        return menu;
    }

    /**
     * Наполнить служебное подменю списка. Повторный вызов заменяет прежний пункт
     * «Разработка», не затрагивая соседние команды (например, ссылку на список).
     */
    static void attach(SubMenu parent, Class<?> type, EntityStructureNavigation navigation,
                       boolean dialog) {
        attach(parent, type, navigation, dialog,
            reason -> Notification.show(reason, 4000, Notification.Position.MIDDLE),
            path -> UI.getCurrent().getPage().executeJs("window.open($0, '_blank', 'noopener');",
                ApplicationBasePath.current() + path), CopyLinkButton::copyAddress);
    }

    static void attach(SubMenu parent, Class<?> type, EntityStructureNavigation navigation,
                       boolean dialog, Consumer<String> refused, Consumer<String> browser,
                       Consumer<String> copy) {
        Optional<MenuItem> previous = parent.getItems().stream()
            .filter(item -> item.getId().map(MENU_ID::equals).orElse(false))
            .findFirst();
        previous.ifPresent(parent::remove);
        if (navigation == null || !navigation.availability(type).available()) {
            return;
        }
        MenuItem developmentItem = parent.addItem("Разработка");
        developmentItem.setId(MENU_ID);
        populate(developmentItem.getSubMenu(), type, navigation, dialog, refused, browser, copy);
    }

    /** Служебная команда списка участвует в общей модели состава. */
    static void attach(org.ipro.form.CommandBar bar, Class<?> type,
                       EntityStructureNavigation navigation, boolean dialog) {
        var id = org.ipro.form.builder.ListUiCommand.DEVELOPMENT.id();
        var command = bar.hasCommand(id) ? bar.command(id)
            : bar.addMenuCommand(id, "Разработка", () -> {}, org.ipro.form.CommandBar.Group.SERVICE);
        command.menuItem().setId(MENU_ID);
        var submenu = command.menuItem().getSubMenu();
        submenu.removeAll();
        boolean available = navigation != null && navigation.availability(type).available();
        if (available) {
            populate(submenu, type, navigation, dialog,
                reason -> Notification.show(reason, 4000, Notification.Position.MIDDLE),
                path -> UI.getCurrent().getPage().executeJs("window.open($0, '_blank', 'noopener');",
                    ApplicationBasePath.current() + path), CopyLinkButton::copyAddress);
        }
        command.setVisible(available);
        bar.refresh();
    }

    private static void populate(SubMenu development, Class<?> type,
                                 EntityStructureNavigation navigation, boolean dialog,
                                 Consumer<String> refused, Consumer<String> browser,
                                 Consumer<String> copy) {
        Optional<String> published = navigation.link(type);
        MenuItem open = development.addItem("Открыть в Entity Explorer", event -> {
            EntityStructureNavigation.Availability available = navigation.availability(type);
            if (!available.available()) {
                refused.accept(available.reason());
            } else if (dialog) {
                navigation.link(type).ifPresentOrElse(browser,
                    () -> refused.accept("Тип не имеет опубликованной ссылки Entity Explorer"));
            } else if (navigation.open(type, null) instanceof EntityStructureNavigation.OpenResult.Unavailable failure) {
                refused.accept(failure.reason());
            }
        });
        if (dialog && published.isEmpty()) {
            open.setEnabled(false);
            open.setText("Entity Explorer недоступен: тип не опубликован");
        }
        if (published.isPresent()) {
            development.addItem("Скопировать ссылку на структуру", event -> {
                EntityStructureNavigation.Availability available = navigation.availability(type);
                if (!available.available()) {
                    refused.accept(available.reason());
                } else {
                    navigation.link(type).ifPresentOrElse(copy,
                        () -> refused.accept("Тип не имеет опубликованной ссылки Entity Explorer"));
                }
            });
        }
    }
}
