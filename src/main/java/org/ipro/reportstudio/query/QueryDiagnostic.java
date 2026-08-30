package org.ipro.reportstudio.query;

import java.util.Objects;

/** Диагностика построения визуального запроса. */
public record QueryDiagnostic(Severity severity, Code code, String message, String path) {
    public QueryDiagnostic {
        severity = Objects.requireNonNull(severity);
        code = Objects.requireNonNull(code);
        message = Objects.requireNonNull(message);
    }
    public enum Severity { INFO, WARNING, ERROR }
    public enum Code { INVALID_MODEL, UNKNOWN_FIELD, UNKNOWN_ENTITY, UNSUPPORTED_FUNCTION, UNSAFE_INPUT, RUNTIME }
}
