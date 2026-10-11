package org.ipro.rest.web.problem;

import jakarta.servlet.http.HttpServletResponse;
import org.ipro.rest.web.codec.RestJsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Single Problem Details writer of the REST read namespace (F-REST-READ-4 §6.2).
 *
 * <p>It serves the MVC advice, the namespace filters and the Basic security entry point /
 * access-denied handler, so statuses, stable codes, headers and body shape cannot drift apart.
 * The {@code byte[]} is produced completely before anything is written, so a failure never
 * leaves a partial JSON document. The {@code WWW-Authenticate: Basic realm="rest-read"} challenge
 * is attached exactly to 401, never to 403/404/405/406/500/503.</p>
 */
public final class RestProblemWriter {

    public static final String BASIC_REALM = "rest-read";
    private static final Logger log = LoggerFactory.getLogger(RestProblemWriter.class);
    // Last resort for bounded scalar Problems if the application's customized mapper fails.
    // This is private, never a Spring mapper/converter bean, and never serializes success payloads.
    private static final JsonMapper FALLBACK = JsonMapper.builder().build();

    private final RestJsonCodec codec;

    public RestProblemWriter(RestJsonCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
    }

    /** MVC advice counterpart: a complete response entity with no-store and the Basic challenge. */
    public ResponseEntity<byte[]> response(int status, String code, String detail,
                                           String param, String reason, String instance) {
        Prepared prepared = prepare(status, code, detail, param, reason, instance);
        return responsePrepared(prepared);
    }

    private ResponseEntity<byte[]> responsePrepared(Prepared prepared) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(prepared.status())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .cacheControl(CacheControl.noStore());
        if (prepared.status() == 401) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, challenge());
        }
        if (prepared.status() == 405) builder.header(HttpHeaders.ALLOW, "GET");
        return builder.body(prepared.bytes());
    }

    /** Servlet-filter/security-handler counterpart: writes status, headers and a complete body. */
    public void write(HttpServletResponse response, int status, String code, String detail,
                      String param, String reason, String instance) throws IOException {
        writePrepared(response, prepare(status, code, detail, param, reason, instance));
    }

    public ResponseEntity<byte[]> failureResponse(Throwable failure, String instance) {
        RestProblemFactory.Failure outcome = RestProblemFactory.failure(failure);
        Prepared prepared = prepare(outcome.status(), outcome.code(), outcome.detail(),
            outcome.param(), outcome.reason(), instance);
        logFailure(prepared, failure);
        return responsePrepared(prepared);
    }

    public void writeFailure(HttpServletResponse response, Throwable failure, String instance) throws IOException {
        RestProblemFactory.Failure outcome = RestProblemFactory.failure(failure);
        Prepared prepared = prepare(outcome.status(), outcome.code(), outcome.detail(),
            outcome.param(), outcome.reason(), instance);
        logFailure(prepared, failure);
        writePrepared(response, prepared);
    }

    private void writePrepared(HttpServletResponse response, Prepared prepared) throws IOException {
        if (response.isCommitted()) throw new IOException("REST response is already committed");
        response.resetBuffer();
        response.setStatus(prepared.status());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, prepared.status() == 401 ? challenge() : null);
        response.setHeader(HttpHeaders.ALLOW, prepared.status() == 405 ? "GET" : null);
        response.setHeader(HttpHeaders.LOCATION, null);
        response.setContentLength(prepared.bytes().length);
        response.getOutputStream().write(prepared.bytes());
        response.flushBuffer();
    }

    private Prepared prepare(int status, String code, String detail, String param, String reason, String instance) {
        Map<String, Object> body = body(status, code, detail, param, reason, instance);
        try {
            return new Prepared(status, codec.writeProblem(body), (String) body.get("traceId"));
        } catch (RuntimeException serializationFailure) {
            Map<String, Object> fallback = body(500, "internal_error", "Unexpected error", null, null, instance);
            Prepared prepared = new Prepared(500, FALLBACK.writeValueAsBytes(fallback), (String) fallback.get("traceId"));
            logFailure(prepared, serializationFailure);
            return prepared;
        }
    }

    private static void logFailure(Prepared prepared, Throwable failure) {
        if (prepared.status() >= 500) {
            log.error("REST read failure traceId={} status={}", prepared.traceId(), prepared.status(), failure);
        }
    }

    private record Prepared(int status, byte[] bytes, String traceId) { }

    private static Map<String, Object> body(int status, String code, String detail,
                                            String param, String reason, String instance) {
        return RestProblemFactory.body(status, RestProblemFactory.titleFor(status), detail, code,
            param, reason, instance);
    }

    private static String challenge() {
        return "Basic realm=\"" + BASIC_REALM + "\"";
    }
}
