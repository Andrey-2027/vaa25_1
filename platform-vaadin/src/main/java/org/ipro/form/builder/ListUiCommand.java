package org.ipro.form.builder;

import org.ipro.form.action.ActionId;

/**
 * Стабильные ключи штатных UI-команд списка, которые не являются CRUD-действиями.
 *
 * <p>Ключ — данные о команде, а не её исполнитель. UI-команды не становятся Spring-бинами
 * действий, не получают фиктивных требований прав и не подменяют серверные полномочия.
 * Предметные команды и команды других модулей передаются как {@link ActionId}.</p>
 */
public enum ListUiCommand {

    VIEWS("ui.list.views"),
    QUICK_SEARCH("ui.list.quick-search"),
    CONDITIONS("ui.list.conditions"),
    FORMATTING("ui.list.formatting"),
    GROUPING("ui.list.grouping"),
    DEVELOPMENT("ui.list.development");

    private final ActionId id;

    ListUiCommand(String key) {
        this.id = ActionId.of(key);
    }

    /** Стабильный ключ команды. */
    public ActionId id() {
        return id;
    }
}
