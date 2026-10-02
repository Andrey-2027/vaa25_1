package org.ipro.rest.api;

/** A fail-fast, structurally invalid application resource declaration. */
public final class RestResourceDeclarationException extends IllegalArgumentException {

    public enum Code {
        INVALID_RESOURCE_KEY,
        INVALID_MAJOR_VERSION,
        RESOURCE_TYPE_REQUIRED,
        INVALID_FIELD_NAME,
        DUPLICATE_FIELD,
        FIELD_TYPE_REQUIRED,
        FIELD_NULLABILITY_REQUIRED,
        INVALID_PROPERTY_PATH,
        INCOMPATIBLE_FIELD_FORMAT,
        INVALID_FILTER_NAME,
        DUPLICATE_FILTER,
        RESERVED_FILTER_NAME,
        FILTER_OPERATOR_REQUIRED,
        FILTER_VALUE_TYPE_REQUIRED,
        FILTER_FIELD_TYPE_MISMATCH,
        INVALID_FILTER_PATH,
        DUPLICATE_CONFIGURATION,
        EMPTY_FIELD_SET,
        UNKNOWN_FIELD,
        DEFAULT_FIELDS_NOT_SUBSET,
        MISSING_ID_FIELD,
        ID_NOT_IN_MAX_FIELDS,
        ID_NOT_IN_DEFAULT_FIELDS,
        DUPLICATE_SORT_FIELD,
        UNSORTABLE_FIELD,
        INVALID_DEFAULT_SORT,
        INVALID_PAGE_SIZE
    }

    private final Code code;
    private final String resourceKey;
    private final String element;

    RestResourceDeclarationException(Code code, String resourceKey, String element, String detail) {
        super("REST resource '" + resourceKey + "' declaration " + code
            + (element == null ? "" : " at '" + element + "'") + ": " + detail);
        this.code = code;
        this.resourceKey = resourceKey;
        this.element = element;
    }

    public Code code() {
        return code;
    }

    public String resourceKey() {
        return resourceKey;
    }

    /** The field, filter, operation or page setting that failed; null for resource-wide errors. */
    public String element() {
        return element;
    }
}
