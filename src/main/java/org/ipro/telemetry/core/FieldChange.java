package org.ipro.telemetry.core;

/**
 * Одно изменённое поле: имя свойства, старое и новое значение
 * (строковое представление; null — значение отсутствует/добавлено/удалено).
 * Для табличных частей вместо этого record используется сводка в
 * {@link FieldAuditAccumulator} (поле-имя строки + счётчики added/removed/changed).
 */
public record FieldChange(String field, String oldValue, String newValue) {
}
