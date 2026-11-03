package org.ipro.design.existing;

import java.util.Objects;

/**
 * A field the tool does not understand well enough to change. It stays in the file untouched; its
 * name and column are still reserved so that a new field cannot collide with it.
 *
 * @param columnName the physical column, or empty when it cannot be determined
 * @param label      the declared label, or empty when there is none or it is not a literal
 * @param javaType   the type as written in the source
 * @param reason     a stable code, for example {@code NO_FIELD_METADATA} or {@code NOT_LITERAL:label}
 */
public record LockedField(String name, String columnName, String label, String javaType, String reason) {
    public LockedField {
        Objects.requireNonNull(name); Objects.requireNonNull(columnName); Objects.requireNonNull(label);
        Objects.requireNonNull(javaType); Objects.requireNonNull(reason);
    }
}
