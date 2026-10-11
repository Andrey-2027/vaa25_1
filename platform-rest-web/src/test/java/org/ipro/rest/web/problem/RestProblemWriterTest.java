package org.ipro.rest.web.problem;

import jakarta.servlet.ServletException;
import org.ipro.rest.service.RestReadException;
import org.ipro.rest.service.RestReadOutcome;
import org.ipro.rest.web.codec.RestJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RestProblemWriterTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SECRET = "secret SQL select password from org.ip.internal.Store REST:items:v1:LIST";

    @Test
    void typedFailureMatrixDoesNotMisclassifyProgrammingOrIntegrityErrors() {
        record Case(Throwable failure, int status, String code) { }
        for (Case test : List.of(
            new Case(new RestReadException(RestReadOutcome.INVALID_REQUEST, SECRET), 400, "invalid_request"),
            new Case(new BadCredentialsException(SECRET), 401, "unauthenticated"),
            new Case(new DisabledException(SECRET), 401, "unauthenticated"),
            new Case(new AccessDeniedException(SECRET), 403, "forbidden"),
            new Case(new RestReadException(RestReadOutcome.RESOURCE_NOT_FOUND, SECRET), 404, "not_found"),
            new Case(new RestReadException(RestReadOutcome.DETAIL_NOT_FOUND, SECRET), 404, "not_found"),
            new Case(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, SECRET), 405, "method_not_allowed"),
            new Case(new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, SECRET), 406, "not_acceptable"),
            new Case(new RestReadException(RestReadOutcome.SECURITY_UNAVAILABLE, SECRET), 503, "security_unavailable"),
            new Case(new AuthenticationServiceException(SECRET), 503, "security_unavailable"),
            new Case(new DataAccessResourceFailureException(SECRET), 503, "security_unavailable"),
            new Case(new TransientDataAccessResourceException(SECRET), 503, "security_unavailable"),
            new Case(new QueryTimeoutException(SECRET), 503, "security_unavailable"),
            new Case(new java.sql.SQLTransientConnectionException(SECRET), 503, "security_unavailable"),
            new Case(new java.sql.SQLNonTransientConnectionException(SECRET), 503, "security_unavailable"),
            new Case(new java.sql.SQLTimeoutException(SECRET), 503, "security_unavailable"),
            new Case(new org.springframework.transaction.CannotCreateTransactionException(SECRET,
                new java.sql.SQLException(SECRET, "08006")), 503, "security_unavailable"),
            new Case(new java.sql.SQLException(SECRET, "42000"), 500, "internal_error"),
            new Case(new ServletException(new DataAccessResourceFailureException(SECRET)), 503, "security_unavailable"),
            new Case(new BadCredentialsException(SECRET, new AuthenticationServiceException(SECRET)), 503, "security_unavailable"),
            new Case(new BadCredentialsException(SECRET, new RestReadException(RestReadOutcome.FORBIDDEN, SECRET)), 401, "unauthenticated"),
            new Case(new RestReadException(RestReadOutcome.FORBIDDEN, SECRET,
                new DataAccessResourceFailureException(SECRET)), 503, "security_unavailable"),
            new Case(new IllegalArgumentException(SECRET), 500, "internal_error"),
            new Case(new DataIntegrityViolationException(SECRET), 500, "internal_error"),
            new Case(new ResponseStatusException(HttpStatus.FOUND, SECRET), 500, "internal_error"))) {
            var writer = new RestProblemWriter(new RestJsonCodec(JSON));
            var response = writer.failureResponse(test.failure(), "/api/v1/items?password=hidden");
            assertThat(response.getStatusCode().value()).as(test.failure().getClass().getName()).isEqualTo(test.status());
            Map<String, Object> body = JSON.readValue(response.getBody(), new TypeReference<>() { });
            assertThat(body).containsEntry("status", test.status()).containsEntry("code", test.code())
                .containsEntry("type", "about:blank").containsEntry("instance", "/api/v1/items");
            assertThat(body.keySet()).containsExactly("type", "title", "status", "detail", "instance", "code", "traceId");
            assertThat(body.toString()).doesNotContain(SECRET, "password=hidden", "org.ip.internal");
            assertThat(UUID.fromString((String) body.get("traceId"))).isNotNull();
            assertThat(response.getHeaders().getFirst("WWW-Authenticate"))
                .isEqualTo(test.status() == 401 ? "Basic realm=\"rest-read\"" : null);
            assertThat(response.getHeaders().getFirst("Allow")).isEqualTo(test.status() == 405 ? "GET" : null);
            assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("application/problem+json");
        }
    }

    @Test
    void fallbackSerializationIsCompleteSafeAndClearsStaleHeadersAndBody() throws Exception {
        RestJsonCodec codec = mock(RestJsonCodec.class);
        when(codec.writeProblem(any())).thenThrow(new IllegalStateException(SECRET));
        RestProblemWriter writer = new RestProblemWriter(codec);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("Location", "/login");
        response.setHeader("WWW-Authenticate", "old challenge");
        response.setHeader("Allow", "POST");
        response.getOutputStream().write("partial success".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writer.writeFailure(response, new BadCredentialsException(SECRET), "/api/v1/items?secret=value");
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
        assertThat(response.getHeader("Allow")).isNull();
        assertThat(response.getContentAsString()).doesNotContain(SECRET, "partial success", "secret=value");
        Map<String, Object> body = JSON.readValue(response.getContentAsByteArray(), new TypeReference<>() { });
        assertThat(body).containsEntry("code", "internal_error").containsEntry("status", 500)
            .containsEntry("instance", "/api/v1/items");
        assertThat(response.getContentLength()).isEqualTo(response.getContentAsByteArray().length);
        assertThat(response.isCommitted()).isTrue();
        var mvcFallback = writer.failureResponse(new IllegalStateException(SECRET), "/api/v1/items");
        assertThat(mvcFallback.getStatusCode().value()).isEqualTo(500);
        assertThat(JSON.readTree(mvcFallback.getBody()).get("code").asString()).isEqualTo("internal_error");
    }

    @Test
    void traceIdsAreGeneratedByServerAndUnsafeParameterNamesAreNotEchoed() {
        var first = RestProblemFactory.body(400, "Invalid request", "Invalid request", "invalid_request",
            "org.ip.secret;select-password", "unknown_parameter", "/api/v1/items?token=secret");
        var second = RestProblemFactory.body(400, "Invalid request", "Invalid request", "invalid_request",
            "size", "invalid_size", "/api/v1/items");
        assertThat(first).doesNotContainKey("param");
        assertThat(first).containsEntry("instance", "/api/v1/items");
        assertThat(second).containsEntry("param", "size");
        assertThat(first.get("traceId")).isNotEqualTo(second.get("traceId"));
    }

    @Test
    void cyclicCauseDoesNotLoopAndCommittedResponseIsNeverRewritten() throws Exception {
        RuntimeException first = new RuntimeException("one");
        RuntimeException second = new RuntimeException("two", first);
        first.initCause(second);
        assertThat(RestProblemFactory.failure(first).status()).isEqualTo(500);
        var writer = new RestProblemWriter(new RestJsonCodec(JSON));
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        response.getOutputStream().write("already committed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        response.flushBuffer();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.writeFailure(response, first, "/api/v1/items"))
            .isInstanceOf(java.io.IOException.class);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("already committed");
    }

    @Test
    void serverLogCarriesTheReturnedTraceIdAndFullCauseWhileWireRemainsSafe() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RestProblemWriter.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var writer = new RestProblemWriter(new RestJsonCodec(JSON));
            var failure = new IllegalStateException(SECRET, new IllegalArgumentException("inner diagnostics"));
            var response = writer.failureResponse(failure, "/api/v1/items");
            Map<String, Object> body = JSON.readValue(response.getBody(), new TypeReference<>() { });
            assertThat(appender.list).hasSize(1);
            var event = appender.list.getFirst();
            assertThat(event.getFormattedMessage()).contains((String) body.get("traceId"), "status=500");
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo(SECRET);
            assertThat(event.getThrowableProxy().getCause().getMessage()).isEqualTo("inner diagnostics");
            assertThat(body.toString()).doesNotContain(SECRET, "inner diagnostics");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
