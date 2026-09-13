package org.ipro.form.builtin;

import org.ipro.metadata.FieldMetadataInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight rollback buffer for a table-section row editor.
 *
 * <p>Captured values are kept as-is, включая захваченные entity-ссылки. C4.1 (ADR-0007 §4):
 * restore больше не перечитывает каждую ссылку отдельным {@code LookupService.findById} —
 * это был UI-managed N+1 ({@code RowDraft} хранил тип+id и резолвил заново), а корректным
 * состоянием отмены является ровно то, которое строка имела до открытия диалога. Побочно
 * исчезает отказ на несохранённой ссылке (id == null), который прежний путь не умел
 * восстановить.</p>
 */
final class RowDraft<T> {
    private final Map<FieldMetadataInfo, Object> values;

    private RowDraft(Map<FieldMetadataInfo, Object> values) {
        this.values = values;
    }

    static <T> RowDraft<T> capture(T row, List<FieldMetadataInfo> fields) {
        Map<FieldMetadataInfo, Object> values = new LinkedHashMap<>();
        for (FieldMetadataInfo field : fields) {
            values.put(field, field.getValue(row));
        }
        return new RowDraft<>(values);
    }

    void restore(T row) {
        for (Map.Entry<FieldMetadataInfo, Object> entry : values.entrySet()) {
            entry.getKey().setValue(row, entry.getValue());
        }
    }
}
