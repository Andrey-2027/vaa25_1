package org.ipro.form.coordinator;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.UI;
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
    private FormStructureAffordance() {}

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
        menu.setId("entity-structure-menu");
        menu.addThemeVariants(MenuBarVariant.LUMO_TERTIARY, MenuBarVariant.LUMO_SMALL);
        var development = menu.addItem("Разработка").getSubMenu();
        Optional<String> published = navigation.link(type);
        var open = development.addItem("Открыть в Entity Explorer", event -> {
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
        ((HasComponents) toolbar).add(menu);
        ComponentUtil.setData(toolbar, FormStructureAffordance.class.getName(), menu);
        return menu;
    }
}
