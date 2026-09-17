package org.ipro.metadata;

/**
 * Одно условие отбора, сохранённое в GridFormView (декларативный отбор — задаётся
 * в редакторе вида, независимо от того, что сейчас введено в живых фильтрах грида).
 *
 * Поля используются по-разному в зависимости от FieldType поля (см. ListForm.applyFilters()):
 *   - TEXT/INTEGER/DECIMAL/PASSWORD/EMAIL: mode = TextFilter.FilterMode.name()
 *     (CONTAINS/EQUALS/STARTS_WITH/ENDS_WITH), value = текст, valueTo не используется
 *   - DATE: value = дата "от" (ISO, может быть null), valueTo = дата "до" (ISO, может
 *     быть null), mode не используется
 *   - ENUM: value = имя константы enum, mode/valueTo не используются
 *   - ENTITY_REFERENCE (только прямое поле, не через точку): value = id связанной
 *     сущности, mode/valueTo не используются
 *
 * path ограничен ПРЯМЫМИ полями сущности (без точки) в первой версии редактора —
 * см. обсуждение ограничений ComboBoxFilter для вложенных путей.
 */
public record FilterSpec(String path, String mode, String value, String valueTo) {
}
