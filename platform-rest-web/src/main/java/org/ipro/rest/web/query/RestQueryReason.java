package org.ipro.rest.web.query;

/**
 * Stable 400 reason codes of the v1 wire contract (F-REST-READ-4 §6.2). The set is extensible only
 * together with the contract/OpenAPI snapshot; each value appears verbatim in the Problem
 * {@code reason} extension.
 */
public enum RestQueryReason {

    UNKNOWN_PARAMETER("unknown_parameter"),
    DUPLICATE_PARAMETER("duplicate_parameter"),
    UNKNOWN_FIELD("unknown_field"),
    DUPLICATE_FIELD("duplicate_field"),
    EMPTY_FIELDS("empty_fields"),
    UNKNOWN_FILTER("unknown_filter"),
    INVALID_FILTER_VALUE("invalid_filter_value"),
    INVALID_PAGE("invalid_page"),
    INVALID_SIZE("invalid_size"),
    OUT_OF_WINDOW("out_of_window"),
    UNKNOWN_SORT("unknown_sort"),
    MULTIPLE_SORT_KEYS("multiple_sort_keys"),
    INVALID_SORT_DIRECTION("invalid_sort_direction"),
    INVALID_ID("invalid_id");

    private final String wireName;

    RestQueryReason(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
