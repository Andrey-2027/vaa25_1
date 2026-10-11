package org.ipro.register;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.validation.constraints.NotNull;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;

import java.time.LocalDateTime;

/** Shared JPA mapping for a periodic information-register row owned by a recorder. */
@MappedSuperclass
@Access(AccessType.FIELD)
@EntityListeners(RegisterPeriodListener.class)
public abstract class PeriodicRecordedRegisterEntryBase extends RecordedRegisterEntryBase
        implements PeriodicRegisterEntry {
    @NotNull
    @Column(name = "period_date", nullable = false, updatable = false)
    @FieldMetadata(
        label = "Период", order = 2,
        grid = @GridColumn(order = 2, width = "150px")
    )
    private LocalDateTime period;

    protected PeriodicRecordedRegisterEntryBase() {
    }

    @Override
    public LocalDateTime getPeriod() {
        return period;
    }

    @Override
    public void setPeriod(LocalDateTime period) {
        this.period = period == null ? null : RegisterPeriodicity.EXACT.normalize(period);
    }
}
