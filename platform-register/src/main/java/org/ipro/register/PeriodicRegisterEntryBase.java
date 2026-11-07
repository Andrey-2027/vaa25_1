package org.ipro.register;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;

import java.time.LocalDateTime;

/** Shared JPA mapping for a standalone periodic information-register row. */
@MappedSuperclass
@Access(AccessType.FIELD)
@EntityListeners(RegisterPeriodListener.class)
public abstract class PeriodicRegisterEntryBase extends BaseEntity implements PeriodicRegisterEntry {
    /**
     * Период показывается колонкой, но без встроенной строки быстрого фильтра: диапазон
     * даты-времени в шапке широкий и перекрывает сам список. Возможность отбора сохранена —
     * условие по периоду по-прежнему разрешено, а дата среза выбирается отдельным контролом
     * «На дату» (см. {@code RegisterFormCustomization}). Правило общее для периодических
     * регистров, поэтому живёт в общем базовом классе, а не в каждом регистре.
     */
    @NotNull
    @Column(name = "period_date", nullable = false, updatable = false)
    @FieldMetadata(
        label = "Период", order = 2,
        grid = @GridColumn(order = 2, width = "150px")
    )
    private LocalDateTime period;

    protected PeriodicRegisterEntryBase() {
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
