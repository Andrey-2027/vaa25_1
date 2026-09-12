package org.ipro.crud;

import org.ipro.crud.IdentifiableEntity;

import java.util.List;

/**
 * Сервис табличной части документа. T — строка (например, ReceivingDocumentItem),
 * P — родительский документ (например, ReceivingDocument).
 *
 * Строки табличной части — отдельные owned entities, не EAGER-коллекция на родителе.
 * UI (ItemTable) работает со списком строк в памяти; стандартную реализацию этого
 * контракта создаёт платформа из resolved descriptor.
 */
public interface TableSectionService<T extends IdentifiableEntity, P extends IdentifiableEntity> {

    /**
     * Загрузить все строки, принадлежащие родителю, в порядке отображения.
     * Для нового (несохранённого) родителя — вызывающая сторона не должна вызывать этот метод,
     * ItemTable сам возвращает пустой список для parent.getId() == null.
     */
    List<T> findByParent(P parent);

    /**
     * Та же загрузка, но с явным набором fetch-путей вместо тех, что сервис использует
     * по умолчанию — нужно, например, когда ItemTable применил сохранённый вид с другим
     * составом колонок. Дефолтная реализация просто игнорирует fetchPaths и делегирует
     * в findByParent(parent) — для реализаций, которые ещё не умеют в явные пути.
     */
    default List<T> findByParent(P parent, java.util.Collection<String> fetchPaths) {
        return findByParent(parent);
    }

    /**
     * Создать новую пустую строку, уже привязанную к parent (для диалога добавления).
     * Родитель может быть ещё не сохранён (id == null) — строка сохранится позже,
     * при вызове replaceAll().
     */
    T createNew(P parent);

    /**
     * Кросс-валидация строк перед сохранением (например, "сумма количества по строкам
     * не может превышать остаток", "не должно быть дублирующихся позиций одной номенклатуры").
     * Возвращает список сообщений об ошибках; пустой список — всё валидно.
     *
     * UI вызывает это как раннюю проверку; authoritative validation повторяется внутри
     * атомарного aggregate save. Предметные cross-section правила оформляются listeners.
     */
    List<String> validateRows(P parent, List<T> rows);

    /**
     * Транзакционно приводит состояние в БД к переданному списку строк:
     * новые (id == null) — insert с автоматически проставленным номером строки,
     * изменённые (id != null, есть в rows) — update,
     * отсутствующие в rows, но существовавшие в БД — delete.
     *
     * Вызывается после успешного сохранения родителя (когда у parent уже есть id).
     */
    void replaceAll(P parent, List<T> rows);
}
