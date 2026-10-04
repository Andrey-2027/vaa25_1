package org.ipro.rls.c5;

import java.util.Objects;

/** Stable, collision-free keys for C5 entity and persistent-attribute permissions. */
public final class C5PermissionKeys {

    private C5PermissionKeys() {
    }

    public static String entityRead(Class<?> entityType) {
        return "ENTITY:" + Objects.requireNonNull(entityType, "entityType must not be null").getName();
    }

    public static String attributeRead(Class<?> entityType, String persistentPath) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(persistentPath, "persistentPath must not be null");
        if (persistentPath.isBlank()) {
            throw new IllegalArgumentException("persistentPath must not be blank");
        }
        return "ATTR:" + entityType.getName() + ":" + persistentPath;
    }
}
