package org.ipro.data;

import java.util.Objects;

/**
 * Явная классификация типа, которую нельзя вывести из аннотаций (C4, ADR-0007 §2).
 *
 * <p>Единственный законный потребитель сейчас — структурная owned-строка без
 * {@code @TableSectionMetadata} (например, {@code SklNomOpaValue}): она принадлежит
 * агрегату, но generic section machinery её не описывает. Объявляется в прикладном слое,
 * чтобы платформенный descriptor catalog не зависел от application model.</p>
 *
 * @param type     классифицируемый persistence type
 * @param exposure явная экспозиция
 * @param owner    владелец агрегата для {@link EntityExposure#OWNED_ROW}; иначе {@code void.class}
 * @param reason   причина решения
 */
public record EntityExposureOverride(Class<?> type,
                                     EntityExposure exposure,
                                     Class<?> owner,
                                     String reason) {

    public EntityExposureOverride {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(exposure, "exposure must not be null");
        owner = owner == null ? void.class : owner;
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
