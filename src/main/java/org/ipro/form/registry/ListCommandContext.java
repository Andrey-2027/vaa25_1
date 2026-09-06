package org.ipro.form.registry;

import org.ipro.form.builtin.ListForm;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.crud.IdentifiableEntity;

/**
 * Контекст выполнения команды списка.
 *
 * <p>Помимо выбранной строки предоставляет параметры открытия и текущие значения
 * контекстной панели. Это позволяет команде строить связь source → target без доступа
 * к приватному состоянию ListForm.</p>
 */
public record ListCommandContext<T extends IdentifiableEntity>(
        ListForm<T, ?> listForm,
        FormCoordinator coordinator) {

    /** Выбранная строка; может быть null для команды без обязательного выделения. */
    public T selectedItem() {
        return listForm.getSelectedItem();
    }

    /** Снимок параметров открытия и фильтров списка на момент обращения. */
    public ListFormContext formContext() {
        return listForm.getContextSnapshot();
    }

    public Object parameter(String name) {
        return formContext().parameter(name);
    }

    public <V> V parameter(String name, Class<V> type) {
        return formContext().parameter(name, type);
    }

    public Object contextFilter(String path) {
        return formContext().filter(path);
    }

    public <V> V contextFilter(String path, Class<V> type) {
        return formContext().filter(path, type);
    }
}
