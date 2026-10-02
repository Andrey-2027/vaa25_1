package org.ipro.rest.catalog;

import java.util.Objects;

/** Named fail-closed startup diagnostic for an invalid REST resource declaration/catalog. */
public final class RestResourceCatalogException extends RuntimeException {

    public enum Code {
        DUPLICATE_RESOURCE,
        NON_SINGLETON_RESOURCE,
        BACKEND_CONTEXT_REQUIRED,
        AMBIGUOUS_PERSISTENCE_UNIT,
        AMBIGUOUS_DESCRIPTOR_CATALOG,
        UNSUPPORTED_PERSISTENCE_PROVIDER,
        INCONSISTENT_BACKEND_METADATA,
        UNMANAGED_RESOURCE_TYPE,
        UNSUPPORTED_EXPOSURE,
        READ_CAPABILITY_REQUIRED,
        INVALID_PERSISTENT_PATH,
        COLLECTION_PATH_NOT_SUPPORTED,
        NON_SCALAR_TERMINAL,
        SCALAR_MAPPING_MISMATCH,
        UNSUPPORTED_SCALAR_MAPPING,
        NULLABILITY_MISMATCH,
        INVALID_ID_MAPPING,
        UNSUPPORTED_ID_MAPPING,
        UNSUPPORTED_FILTER_PATH,
        UNSUPPORTED_SORT_PATH
    }

    private final Code code;
    private final String beanName;
    private final RestResourceKey key;
    private final String element;
    private final String sourcePath;

    public RestResourceCatalogException(Code code, String beanName, RestResourceKey key,
                                 String element, String sourcePath, String detail) {
        super(message(code, beanName, key, element, sourcePath, detail));
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.beanName = beanName == null ? "" : beanName;
        this.key = key;
        this.element = element == null ? "" : element;
        this.sourcePath = sourcePath == null ? "" : sourcePath;
    }

    public RestResourceCatalogException(Code code, String beanName, RestResourceKey key,
                                 String element, String sourcePath, String detail, Throwable cause) {
        super(message(code, beanName, key, element, sourcePath, detail), cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.beanName = beanName == null ? "" : beanName;
        this.key = key;
        this.element = element == null ? "" : element;
        this.sourcePath = sourcePath == null ? "" : sourcePath;
    }

    public Code code() { return code; }
    public String beanName() { return beanName; }
    public RestResourceKey key() { return key; }
    public String element() { return element; }
    public String sourcePath() { return sourcePath; }

    private static String message(Code code, String beanName, RestResourceKey key,
                                  String element, String sourcePath, String detail) {
        StringBuilder message = new StringBuilder("REST catalog ").append(code);
        if (beanName != null && !beanName.isBlank()) message.append(" bean=").append(beanName);
        if (key != null) message.append(" resource=").append(key.resource()).append(" major=").append(key.major());
        if (element != null && !element.isBlank()) message.append(" element=").append(element);
        if (sourcePath != null && !sourcePath.isBlank()) message.append(" path=").append(sourcePath);
        if (detail != null && !detail.isBlank()) message.append(": ").append(detail);
        return message.toString();
    }
}
