package org.ipro.rls.c5;

import java.util.Objects;

/** Stable keys for C5 entity and named operation permissions. */
public final class C5PermissionKeys {

    private C5PermissionKeys() {
    }

    public static String entityRead(Class<?> entityType) {
        return "ENTITY:" + Objects.requireNonNull(entityType, "entityType must not be null").getName();
    }

    /**
     * Key of the entity operation definition. Uses the existing entity gate key
     * {@code ENTITY:<SimpleName>} declared by {@code @RlsDimension(kind = CHECK_ONLY)} — ADR-0017 §3
     * keeps it unchanged so already issued rows and the pilot fixture keep working. The fully
     * qualified read key above is a different definition of the same type; that double spelling is
     * the known naming debt, not an accidental collision.
     *
     * <p>Simple names are unique among managed types in this application: the RLS registry already
     * fails at startup on a duplicate dimension name.</p>
     */
    public static String entityOperations(Class<?> entityType) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        return "ENTITY:" + entityType.getSimpleName();
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
