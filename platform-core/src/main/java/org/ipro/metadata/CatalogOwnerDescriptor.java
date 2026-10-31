package org.ipro.metadata;

import java.util.Objects;

/**
 * Resolved runtime descriptor владельца справочника.
 *
 * <p>Публикуется только для типа с валидной объявленной capability: отсутствие descriptor'а —
 * обычный справочник без owner, а не «не удалось прочитать». Тип цели берётся из объявленной
 * ассоциации, поэтому property и targetTypeId всегда согласованы.</p>
 *
 * <p>Descriptor — платформенный DTO. Portable facts для design-runtime строятся мостом
 * {@code entity-design-runtime}; ни platform-core, ни entity-design-core не импортируют DTO
 * друг друга.</p>
 *
 * @param property     имя Java-поля-владельца
 * @param targetTypeId fully-qualified имя типа-владельца
 */
public record CatalogOwnerDescriptor(String property, String targetTypeId) {

    public CatalogOwnerDescriptor {
        Objects.requireNonNull(property, "property must not be null");
        Objects.requireNonNull(targetTypeId, "targetTypeId must not be null");
        if (property.isBlank()) {
            throw new IllegalArgumentException("owner property must not be blank");
        }
        if (targetTypeId.isBlank()) {
            throw new IllegalArgumentException("owner targetTypeId must not be blank");
        }
    }
}
