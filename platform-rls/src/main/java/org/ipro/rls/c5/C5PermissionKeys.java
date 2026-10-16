package org.ipro.rls.c5;

import java.util.Objects;

/** Stable keys for C5 entity and named operation permissions. */
public final class C5PermissionKeys {

    private C5PermissionKeys() {
    }

    public static String entityRead(Class<?> entityType) {
        return entity(entityType);
    }

    /** One entity identity for every supported C5 operation. */
    public static String entity(Class<?> entityType) {
        return "ENTITY:" + Objects.requireNonNull(entityType, "entityType must not be null").getName();
    }

    /**
     * Entity operations use the same canonical identity as reading. The former short-name
     * key is not an alias and existing development grants are discarded, not migrated (P-U).
     */
    public static String entityOperations(Class<?> entityType) {
        return entity(entityType);
    }

    /** Key of a named owner operation; the id is stable and never derived from a label or a bean name. */
    public static String action(String permissionId) {
        Objects.requireNonNull(permissionId, "permissionId must not be null");
        if (permissionId.isBlank() || !permissionId.equals(permissionId.trim())) {
            throw new IllegalArgumentException(
                "C5 action permission id must be a non-blank name without outer spaces: '" + permissionId + "'");
        }
        return "ACTION:" + permissionId;
    }

    /** @deprecated Legacy spelling only; read services resolve it to the owning entity key. */
    @Deprecated
    public static String attributeRead(Class<?> entityType, String persistentPath) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(persistentPath, "persistentPath must not be null");
        if (persistentPath.isBlank()) {
            throw new IllegalArgumentException("persistentPath must not be blank");
        }
        return "ATTR:" + entityType.getName() + ":" + persistentPath;
    }

    /** Legacy ATTR keys are aliases of entity read, never independent grant lookups. */
    static String readKey(String key) {
        Objects.requireNonNull(key, "key");
        if (!key.startsWith("ATTR:")) return key;
        int separator = key.indexOf(':', "ATTR:".length());
        if (separator <= "ATTR:".length() || separator == key.length() - 1) {
            throw new IllegalArgumentException("Malformed legacy read key");
        }
        return "ENTITY:" + key.substring("ATTR:".length(), separator);
    }
}
