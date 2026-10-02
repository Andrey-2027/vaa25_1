package org.ipro.data;

/**
 * Diagnostic exception for REST graph profile and fetch validation errors (F-REST-READ-3).
 */
public class RestGraphException extends RuntimeException {

    public enum Code {
        MISSING_PATHS,
        MISSING_IDENTIFIER_PATH,
        BARE_ASSOCIATION_NOT_ALLOWED,
        COLLECTION_PATH_NOT_SUPPORTED,
        INVALID_PERSISTENT_PATH
    }

    private final Code code;
    private final Class<?> entityType;
    private final String path;

    public RestGraphException(Code code, Class<?> entityType, String path, String message) {
        super(String.format("[%s] type=%s, path=%s: %s",
            code, entityType != null ? entityType.getName() : "null", path != null ? path : "", message));
        this.code = code;
        this.entityType = entityType;
        this.path = path;
    }

    public Code code() { return code; }
    public Class<?> entityType() { return entityType; }
    public String path() { return path; }
}
