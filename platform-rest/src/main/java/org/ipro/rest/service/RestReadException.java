package org.ipro.rest.service;

import java.util.Objects;

/**
 * Diagnostic exception for REST pipeline execution errors (F-REST-READ-3 §6.2).
 */
public class RestReadException extends RuntimeException {

    private final RestReadOutcome outcome;

    public RestReadException(RestReadOutcome outcome, String message) {
        super(String.format("[%s] %s", outcome, message));
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
    }

    public RestReadOutcome outcome() {
        return outcome;
    }
}
