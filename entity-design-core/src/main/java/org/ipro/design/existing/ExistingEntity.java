package org.ipro.design.existing;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.ipro.design.EntityDraft;

/**
 * An entity as it is written in its Java file: the readable part as an {@link EntityDraft} marked
 * with {@code RecipeCatalog.EXISTING_ENTITY}, the rest as locked fields and reserved names.
 *
 * @param typeId              the fully qualified class name
 * @param relativePath        the file path under its source root, always with {@code /}
 * @param sourceSha256        SHA-256 of the file bytes as read
 * @param tableName           the physical table, or empty when it cannot be determined
 * @param inheritedNames      fields declared by the base classes; a new field may not take them
 * @param occupiedMethodNames methods declared by the class, its bases and its known subclasses
 */
public record ExistingEntity(String typeId, String relativePath, String sourceSha256, String tableName,
                             EntityShape shape, EntityDraft draft, List<LockedField> lockedFields,
                             Set<String> inheritedNames, Set<String> occupiedMethodNames) {
    public ExistingEntity {
        Objects.requireNonNull(typeId); Objects.requireNonNull(relativePath); Objects.requireNonNull(sourceSha256);
        Objects.requireNonNull(tableName); Objects.requireNonNull(shape); Objects.requireNonNull(draft);
        lockedFields = List.copyOf(lockedFields);
        inheritedNames = Set.copyOf(inheritedNames);
        occupiedMethodNames = Set.copyOf(occupiedMethodNames);
    }
}
