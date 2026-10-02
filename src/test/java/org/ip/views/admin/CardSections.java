package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasText;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.tabs.TabSheet;

import java.util.ArrayList;
import java.util.List;

/**
 * Общий обход заголовков карточки для тестов. Содержимое вкладок {@link TabSheet} прикрепляется к
 * DOM отложенно (перед ответом клиенту), поэтому обычным обходом дерева его не видно; здесь
 * заголовки читаются по API вкладок, а внутри вкладки — обычным обходом. Копия этого обхода в
 * каждом тесте разошлась бы при первом же изменении рендера, поэтому она одна.
 */
public final class CardSections {

    private CardSections() {
    }

    /** Заголовки разделов в порядке экрана: вкладки по порядку, разделы внутри вкладки. */
    public static List<String> titles(Component root) {
        List<String> titles = new ArrayList<>();
        collect(root, titles);
        return titles;
    }

    private static void collect(Component component, List<String> titles) {
        if (component instanceof TabSheet tabs) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                collect(tabs.getComponent(tabs.getTabAt(i)), titles);
            }
            return;
        }
        if (component instanceof H4 heading) {
            titles.add(heading.getText());
        }
        component.getChildren().forEach(child -> collect(child, titles));
    }

    /** Первая вкладка ({@link TabSheet}) внутри компонента: у карточки — вкладки аспектов. */
    public static TabSheet tabs(Component root) {
        if (root instanceof TabSheet tabs) {
            return tabs;
        }
        return root.getChildren()
            .map(CardSections::tabs)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    /** Секция по заголовку: её {@link Details} ищется по API вкладок, внутри — обычным обходом. */
    public static Details details(Component root, String title) {
        TabSheet tabs = tabs(root);
        if (tabs == null) {
            return detailsIn(root, title);
        }
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Details found = detailsIn(tabs.getComponent(tabs.getTabAt(i)), title);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Details detailsIn(Component component, String title) {
        if (component instanceof Details details && hasHeading(details, title)) {
            return details;
        }
        return component.getChildren()
            .map(child -> detailsIn(child, title))
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    private static boolean hasHeading(Component component, String title) {
        if (component instanceof H4 heading && title.equals(heading.getText())) {
            return true;
        }
        return component.getChildren().anyMatch(child -> hasHeading(child, title));
    }

    /** Текст компонента: собственный или собранный из вложенных текстовых компонентов. */
    public static String textIn(Component component) {
        StringBuilder text = new StringBuilder();
        collectText(component, text);
        return text.toString();
    }

    private static void collectText(Component component, StringBuilder text) {
        if (component instanceof TabSheet tabs) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                collectText(tabs.getComponent(tabs.getTabAt(i)), text);
            }
            return;
        }
        if (component instanceof HasText hasText) {
            text.append(hasText.getText());
        }
        component.getChildren().forEach(child -> collectText(child, text));
    }

    /** Первая кнопка внутри компонента: кнопка перехода, если она там есть. */
    public static Button buttonIn(Component component) {
        if (component instanceof Button button) {
            return button;
        }
        return component.getChildren()
            .map(CardSections::buttonIn)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    }
}
