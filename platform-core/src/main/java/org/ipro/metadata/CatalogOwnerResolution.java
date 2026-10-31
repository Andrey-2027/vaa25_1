package org.ipro.metadata;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Результат разбора объявленной owner-capability: resolved descriptor либо адресные
 * диагностики, объясняющие, почему объявление не поддержано.
 *
 * <p>Ошибки не бросаются: startup-валидатор собирает все диагностики сразу, а
 * {@link MetadataConsistencyStartupCheck} решает, останавливать ли старт. Валидный результат
 * не содержит ERROR-диагностик, невалидный не содержит descriptor'а.</p>
 */
public record CatalogOwnerResolution(CatalogOwnerDescriptor owner,
                                     List<MetadataDiagnostic> diagnostics) {

    public CatalogOwnerResolution {
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
        if (owner != null && diagnostics.stream()
                .anyMatch(d -> d.severity() == MetadataDiagnostic.Severity.ERROR)) {
            throw new IllegalArgumentException("invalid owner resolution must not expose a descriptor");
        }
    }

    /** Объявления нет: это не ошибка, а обычный справочник. */
    public static CatalogOwnerResolution none() {
        return new CatalogOwnerResolution(null, List.of());
    }

    public boolean declared() {
        return owner != null || !diagnostics.isEmpty();
    }

    public boolean valid() {
        return owner != null;
    }

    public Optional<CatalogOwnerDescriptor> descriptor() {
        return Optional.ofNullable(owner);
    }
}
