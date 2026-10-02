package org.ipro.rest.api;

/** Entry point for application-owned REST resource declarations. */
public final class RestResources {

    private RestResources() {
    }

    /**
     * Starts a declaration for a public REST key and its independent major contract version.
     * This method does not inspect the Java type or register an HTTP route.
     */
    public static <T> RestResourceBuilder<T> publish(String resourceKey, int majorVersion,
                                                      Class<T> resourceType) {
        return new RestResourceBuilder<>(resourceKey, majorVersion, resourceType);
    }

    /** Creates a declarative Java-property path; its JPA meaning is checked by a later catalog. */
    public static RestPropertyPath path(String expression) {
        return new RestPropertyPath(expression);
    }
}
