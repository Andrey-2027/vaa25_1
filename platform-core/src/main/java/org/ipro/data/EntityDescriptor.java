package org.ipro.data;

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
                               String reason) {

    public EntityDescriptor {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(exposure, "exposure must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
