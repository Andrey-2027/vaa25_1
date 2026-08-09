package org.ipro.telemetry.api;

import java.time.Instant;

/**
 * Запись изменения полей сущности (field-level audit): одна строка на факт
 * изменения записи (а не на изменённое поле). Список изменений — JSON-массив
 * в {@code payload}: {@code [{"field":"price","old":"100","new":"150"}, ...]};
 * для табличных частей — сводки {@code {"field":"<Row>","added":N,"removed":N,"changed":N}}.
 */
public record FieldChangeRecord(
        Instant changedAt,
        String changeType,   // INSERT | UPDATE | DELETE
        String entity,
        String entityId,
        String userId,
        String traceId,
        int fieldCount,
        String payload) {
}
