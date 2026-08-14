package org.ip.form.builtin;

import org.ip.metadata.FieldMetadataInfo;
import org.ip.service.LookupService;
import org.ipro.crud.IdentifiableEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight rollback buffer for a table-section row editor.
 * Scalar values are retained as-is; entity references are stored by type and id.
 */
final class RowDraft<T> {
    private final Map<FieldMetadataInfo, Object> values;

    private RowDraft(Map<FieldMetadataInfo, Object> values) {
        this.values = values;
    }

    static <T> RowDraft<T> capture(T row, List<FieldMetadataInfo> fields) {
        Map<FieldMetadataInfo, Object> values = new LinkedHashMap<>();
        for (FieldMetadataInfo field : fields) {
            Object value = field.getValue(row);
            values.put(field, value instanceof IdentifiableEntity entity
                    ? new EntityReference(field.getJavaType(), entity.getId())
                    : value);
        }
        return new RowDraft<>(values);
    }

    void restore(T row, LookupService lookupService) {
        for (Map.Entry<FieldMetadataInfo, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof EntityReference reference) {
                Object resolved = lookupService.findById(reference.type(), reference.id())
                        .orElseThrow(() -> new IllegalStateException("Unable to restore row reference"));
                entry.getKey().setValue(row, resolved);
            } else {
                entry.getKey().setValue(row, value);
            }
        }
    }

    private record EntityReference(Class<?> type, Object id) {
    }
}
