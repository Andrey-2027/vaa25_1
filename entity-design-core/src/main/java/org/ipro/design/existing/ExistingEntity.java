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
 * @param inheritedColumns    the physical columns of the fields of the base classes; a new field may not take them
 * @param unreadableColumns   fields of the base classes (as {@code Class.field}) whose column cannot be told from the
 *                            sources, and {@code @AttributeOverride} on a base: while there are any, no column can be proposed
 */
public record ExistingEntity(String typeId, String relativePath, String sourceSha256, String tableName,
                             EntityShape shape, EntityDraft draft, List<LockedField> lockedFields,
                             Set<String> inheritedNames, Set<String> occupiedMethodNames,
                             Set<String> inheritedColumns, Set<String> unreadableColumns) {
    public ExistingEntity {
        Objects.requireNonNull(typeId); Objects.requireNonNull(relativePath); Objects.requireNonNull(sourceSha256);
        Objects.requireNonNull(tableName); Objects.requireNonNull(shape); Objects.requireNonNull(draft);
        lockedFields = List.copyOf(lockedFields);
        inheritedNames = Set.copyOf(inheritedNames);
        occupiedMethodNames = Set.copyOf(occupiedMethodNames);
        inheritedColumns = Set.copyOf(inheritedColumns);
        unreadableColumns = Set.copyOf(unreadableColumns);
    }

    /** An entity whose bases are known to add no columns of their own beyond the names. */
    public ExistingEntity(String typeId, String relativePath, String sourceSha256, String tableName,
                          EntityShape shape, EntityDraft draft, List<LockedField> lockedFields,
                          Set<String> inheritedNames, Set<String> occupiedMethodNames) {
        this(typeId, relativePath, sourceSha256, tableName, shape, draft, lockedFields, inheritedNames, occupiedMethodNames, Set.of(), Set.of());
    }
}
