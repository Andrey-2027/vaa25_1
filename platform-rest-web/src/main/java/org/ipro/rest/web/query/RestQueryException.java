package org.ipro.rest.web.query;

import java.util.Objects;

/**
 * Strict-grammar failure of the v1 query parser (F-REST-READ-4 §2.2), mapped to HTTP 400 with the
 * public {@code param} name and the stable {@code reason} code. The offending value is never
 * echoed: the message stays a safe diagnostic for the log, not a wire field.
 */
public class RestQueryException extends RuntimeException {

    private final String param;
    private final RestQueryReason reason;

    public RestQueryException(String param, RestQueryReason reason, String detail) {
        super(detail);
        this.param = Objects.requireNonNull(param, "param must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public String param() {
        return param;
    }

    public RestQueryReason reason() {
        return reason;
    }
}
