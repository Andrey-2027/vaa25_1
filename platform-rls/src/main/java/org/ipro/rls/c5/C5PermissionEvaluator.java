package org.ipro.rls.c5;

/**
 * Common C5 permission evaluator for API operations and entity read.
 * Governed by platform-rls as the owner of C5 decision service and grant store.
 */
public interface C5PermissionEvaluator {

    /**
     * Checks if the given subject has the specified API permission.
     * The permission key is formatted as: REST:{resourceKey}:v{major}:{operation}
     */
    boolean isApiPermitted(String username, String resourceKey, int major, String operation);

    /**
     * Checks if the given subject has C5 read permission for the entity type.
     * The permission key is formatted as ENTITY:{entityType.getName()}.
     */
    boolean isEntityReadPermitted(String username, Class<?> entityType);

    /**
     * @deprecated Fields have no separate permissions; checks the owning entity read permission.
     */
    @Deprecated
    default boolean isAttributeReadPermitted(String username, Class<?> entityType, String persistentPath) {
        return isEntityReadPermitted(username, entityType);
    }
}
