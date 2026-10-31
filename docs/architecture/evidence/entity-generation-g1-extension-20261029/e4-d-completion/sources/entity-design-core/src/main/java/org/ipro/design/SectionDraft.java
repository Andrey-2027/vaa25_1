package org.ipro.design;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SectionDraft(UUID id, String label, String rowClassName, DraftText packageName,
                           DraftText tableName, DraftText parentFieldName, DraftText parentColumnName,
                           List<FieldDraft> fields) {
    public SectionDraft {
        Objects.requireNonNull(id); Objects.requireNonNull(label); Objects.requireNonNull(rowClassName);
        Objects.requireNonNull(packageName); Objects.requireNonNull(tableName);
        Objects.requireNonNull(parentFieldName); Objects.requireNonNull(parentColumnName);
        fields = List.copyOf(fields);
    }
    public SectionDraft withFields(List<FieldDraft> value) { return new SectionDraft(id, label, rowClassName, packageName, tableName, parentFieldName, parentColumnName, value); }
}
