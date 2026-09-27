package org.ipro.data;

import org.ipro.metadata.FactOrigin;

import java.util.Objects;

/**
 * Классифицированный descriptor persistence type (C4, ADR-0007 §2).
 *
 * <p>Строится поверх существующих {@code ManagedEntityCatalog} и
 * {@code SectionMetadataRegistry} без нового classpath scan. Хранит JPA/metadata-признаки,
 * экспозицию, capabilities и причину решения.</p>
 */
public record EntityDescriptor(Class<?> type,
                               EntityExposure exposure,
                               boolean jpaManaged,
                               boolean metadataDriven,
                               EntityCapabilities capabilities,
                               String reason,
                               FactOrigin exposureOrigin,
                               String exposureSymbol) {

    /** Совместимый конструктор для callers, которым происхождение ещё неизвестно. */
    public EntityDescriptor(Class<?> type, EntityExposure exposure, boolean jpaManaged,
                            boolean metadataDriven, EntityCapabilities capabilities, String reason) {
        this(type, exposure, jpaManaged, metadataDriven, capabilities, reason,
            FactOrigin.UNKNOWN, "");
    }

    public EntityDescriptor {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(exposure, "exposure must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(exposureOrigin, "exposureOrigin must not be null");
        exposureSymbol = exposureSymbol == null ? "" : exposureSymbol;
        if (exposureOrigin == FactOrigin.UNKNOWN && !exposureSymbol.isEmpty()) {
            throw new IllegalArgumentException("UNKNOWN exposure origin must not have a symbol");
        }
    }
}
