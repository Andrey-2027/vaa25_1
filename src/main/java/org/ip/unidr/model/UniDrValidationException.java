package org.ip.unidr.model;

import java.util.List;
import java.util.stream.Collectors;

/** Structured diagnostics can be displayed next to their setting in the future editor. */
public final class UniDrValidationException extends IllegalArgumentException {
    private final List<Issue> issues;
    public UniDrValidationException(List<Issue> issues) {
        super(issues.stream().map(issue -> issue.path() + ": " + issue.message()).collect(Collectors.joining("; ")));
        this.issues = List.copyOf(issues);
    }
    public List<Issue> issues() { return issues; }
    public record Issue(Code code, String path, String message) {}
    public enum Code {
        REQUIRED, INVALID_JSON, UNSUPPORTED_SCHEMA, UNKNOWN_SOURCE, INCOMPATIBLE_SOURCE,
        UNKNOWN_FIELD, DUPLICATE, TOO_MANY, INVALID_COLUMN, INVALID_MEASURE, INVALID_GROUP,
        INVALID_SORT, INVALID_FILTER, INVALID_PARAMETER, INVALID_VALUE, TYPE_MISMATCH,
        MISSING_PARAMETER, MISSING_MEASURE, MISSING_COLUMNS, CONFLICTING_FILTER
    }
}
