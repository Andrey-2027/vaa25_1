package org.ipro.register;

import org.ipro.crud.AggregateSaveContribution;
import org.ipro.crud.BaseEntity;
import org.ipro.events.AggregateSection;

import java.util.List;
import java.util.Objects;

/**
 * Server-side replacement payload. Absence from an aggregate save preserves the set;
 * an attached empty row list explicitly clears it.
 */
public final class RecorderSetContribution implements AggregateSaveContribution {
    private final Class<? extends BaseEntity> registerType;
    private final List<? extends BaseEntity> rows;

    public RecorderSetContribution(Class<? extends BaseEntity> registerType,
                                   List<? extends BaseEntity> rows) {
        this.registerType = Objects.requireNonNull(registerType, "registerType");
        this.rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        if (!RecordedRegisterEntry.class.isAssignableFrom(registerType)) {
            throw new IllegalArgumentException("RECORDER_REGISTER_REQUIRED: " + registerType.getName());
        }
        if (this.rows.stream().anyMatch(row -> row.getClass() != registerType)) {
            throw new IllegalArgumentException("RECORDER_ROW_TYPE_MISMATCH: " + registerType.getName());
        }
    }

    @Override
    public String key() {
        return "recorder-set:" + registerType.getName();
    }

    public Class<? extends BaseEntity> registerType() {
        return registerType;
    }

    public List<? extends BaseEntity> rows() {
        return rows;
    }

    @Override
    public List<AggregateSection> eventSections() {
        return List.of(new AggregateSection(registerType, rows));
    }
}
