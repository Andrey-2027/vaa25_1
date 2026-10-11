package org.ipro.rest.web.problem;

import org.ipro.rest.service.RestReadOutcome;
import org.ipro.rest.service.RestReadException;
import org.ipro.rest.web.query.RestQueryException;
import org.ipro.rest.web.query.RestQueryReason;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.ErrorResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.ArrayList;

/**
 * Single Problem Details writer of the REST read namespace (F-REST-READ-4 §6.2).
 *
 * <p>One factory serves the MVC advice, the security entry points and the namespace filters, so
 * statuses, stable codes and safe details cannot drift apart. Stack traces, permission keys, SQL
 * and exception messages never leave the server; {@code param} carries the public parameter name
 * and {@code reason} a stable parser code, while the supplied value itself is not echoed.</p>
 */
public final class RestProblemFactory {

    private RestProblemFactory() {
    }

    /** Problem body in the v1 field order; {@code param}/{@code reason} are 400 extensions. */
    public static Map<String, Object> body(int status, String title, String detail, String code,
                                           String param, String reason, String instance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", Objects.requireNonNull(title, "title must not be null"));
        body.put("status", status);
        body.put("detail", Objects.requireNonNull(detail, "detail must not be null"));
        body.put("instance", instance == null ? "" : instance.split("\\?", 2)[0]);
        body.put("code", Objects.requireNonNull(code, "code must not be null"));
        // Only a public identifier may be echoed; arbitrary raw query keys are untrusted input.
        if (param != null && param.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
            body.put("param", param);
        }
        if (reason != null) {
            body.put("reason", reason);
        }
        body.put("traceId", UUID.randomUUID().toString());
        return body;
    }

    public static int statusForOutcome(RestReadOutcome outcome) {
        return switch (outcome) {
            case UNAUTHENTICATED -> 401;
            case FORBIDDEN -> 403;
            case RESOURCE_NOT_FOUND, DETAIL_NOT_FOUND -> 404;
            case INVALID_REQUEST -> 400;
            case SECURITY_UNAVAILABLE -> 503;
            case SUCCESS -> 500;
        };
    }

    public static String codeForOutcome(RestReadOutcome outcome) {
        return switch (outcome) {
            case UNAUTHENTICATED -> "unauthenticated";
            case FORBIDDEN -> "forbidden";
            case RESOURCE_NOT_FOUND, DETAIL_NOT_FOUND -> "not_found";
            case INVALID_REQUEST -> "invalid_request";
            case SECURITY_UNAVAILABLE -> "security_unavailable";
            case SUCCESS -> "internal_error";
        };
    }

    public static String detailForOutcome(RestReadOutcome outcome) {
        return switch (outcome) {
            case UNAUTHENTICATED -> "Authentication is required for the REST read API";
            case FORBIDDEN -> "Access to the requested resource is denied";
            case RESOURCE_NOT_FOUND, DETAIL_NOT_FOUND -> "Resource not found";
            case INVALID_REQUEST -> "Invalid request";
            case SECURITY_UNAVAILABLE -> "Security enforcement is unavailable";
            case SUCCESS -> "Unexpected error";
        };
    }

    public static String titleFor(int status) {
        return switch (status) {
            case 400 -> "Invalid request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 503 -> "Service Unavailable";
            default -> "Internal Server Error";
        };
    }

    /**
     * Authentication failure split (§6.2): an established infrastructure fault of the provider
     * is a 503, everything else (bad credential, disabled account, anonymous) is a 401. The
     * flapping of a broken backend is never masked as an unauthenticated client.
     */
    public static int statusForAuthenticationFailure(AuthenticationException failure) {
        return failure(failure).status();
    }

    public static String codeForAuthenticationFailure(int status) {
        return status == 503 ? "security_unavailable" : "unauthenticated";
    }

    public static String detailForAuthenticationFailure(int status) {
        return status == 503
            ? "Security enforcement is unavailable"
            : "Authentication is required for the REST read API";
    }

    /** Classification uses semantic types, never exception text or a blanket DataAccessException. */
    static Failure failure(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var causes = new ArrayList<Throwable>();
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            causes.add(current);
        }
        for (Throwable current : causes) {
            if (current instanceof AuthenticationServiceException
                    || current instanceof DataAccessResourceFailureException
                    || current instanceof TransientDataAccessResourceException
                    || current instanceof QueryTimeoutException
                    || current instanceof java.sql.SQLTransientConnectionException
                    || current instanceof java.sql.SQLNonTransientConnectionException
                    || current instanceof java.sql.SQLTimeoutException
                    || current instanceof java.sql.SQLException sql && sql.getSQLState() != null
                        && sql.getSQLState().startsWith("08")
                    || current instanceof RestReadException read && read.outcome() == RestReadOutcome.SECURITY_UNAVAILABLE) {
                return statusFailure(503);
            }
        }
        for (Throwable current : causes) {
            if (current instanceof RestQueryException query) {
                return new Failure(400, "invalid_request", detailFor(query.reason()),
                    query.param(), query.reason().wireName());
            }
            if (current instanceof RestReadException read) {
                return new Failure(statusForOutcome(read.outcome()), codeForOutcome(read.outcome()),
                    detailForOutcome(read.outcome()), null, null);
            }
            if (current instanceof AuthenticationException) return statusFailure(401);
            if (current instanceof AccessDeniedException) return statusFailure(403);
            if (current instanceof ErrorResponse framework) {
                return statusFailure(framework.getStatusCode().value());
            }
            if (current instanceof org.springframework.beans.TypeMismatchException
                    || current instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
                return statusFailure(400);
            }
        }
        return statusFailure(500);
    }

    private static Failure statusFailure(int status) {
        return switch (status) {
            case 400 -> new Failure(400, "invalid_request", "Invalid request", null, null);
            case 401 -> new Failure(401, "unauthenticated", detailForAuthenticationFailure(401), null, null);
            case 403 -> new Failure(403, "forbidden", "Access to the requested resource is denied", null, null);
            case 404 -> new Failure(404, "not_found", "Resource not found", null, null);
            case 405 -> new Failure(405, "method_not_allowed", "Only GET is supported for the REST read API", null, null);
            case 406 -> new Failure(406, "not_acceptable", "Requested representation is not available", null, null);
            case 503 -> new Failure(503, "security_unavailable", detailForAuthenticationFailure(503), null, null);
            default -> new Failure(500, "internal_error", "Unexpected error", null, null);
        };
    }

    record Failure(int status, String code, String detail, String param, String reason) { }

    /** Safe human detail for each stable parser reason; never contains the supplied value. */
    public static String detailFor(RestQueryReason reason) {
        return switch (reason) {
            case UNKNOWN_PARAMETER -> "Unknown query parameter";
            case DUPLICATE_PARAMETER -> "Query parameter must be supplied exactly once";
            case UNKNOWN_FIELD -> "Unknown field alias";
            case DUPLICATE_FIELD -> "Duplicate field alias";
            case EMPTY_FIELDS -> "Field selection must not be empty";
            case UNKNOWN_FILTER -> "Field is not enabled as a filter";
            case INVALID_FILTER_VALUE -> "Filter value has an invalid type";
            case INVALID_PAGE -> "Page index must be a non-negative integer";
            case INVALID_SIZE -> "Page size is outside the published range";
            case OUT_OF_WINDOW -> "Requested page exceeds the configured window";
            case UNKNOWN_SORT -> "Sort alias is not declared";
            case MULTIPLE_SORT_KEYS -> "Only one sort key is allowed";
            case INVALID_SORT_DIRECTION -> "Sort direction must be 'asc' or 'desc'";
            case INVALID_ID -> "Detail id has an invalid type";
        };
    }
}
