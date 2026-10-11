package org.ipro.rest.web.security;

import jakarta.servlet.FilterChain;
import org.ipro.rest.gateway.RestReadGateway;
import org.ipro.rest.gateway.RestReadResourceSchema;
import org.ipro.rest.web.codec.RestJsonCodec;
import org.ipro.rest.web.problem.RestProblemWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-REST-READ-4 §5.2: fixed precedence 404 before 405 before 406, no-store on every outcome,
 * and no grant/business read from the namespace filter.
 */
class RestReadNamespaceFilterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RestReadGateway gateway;
    private RestReadNamespaceFilter filter;

    @BeforeEach
    void setUp() {
        gateway = mock(RestReadGateway.class);
        RestProblemWriter writer = new RestProblemWriter(new RestJsonCodec(JSON));
        filter = new RestReadNamespaceFilter(gateway, new RestRequestTargetParser(), writer);
    }

    @Test
    void knownGetRequestPassesWithNoStore() throws Exception {
        known();

        MockHttpServletResponse response = run("GET", "/api/v1/specifications", null);

        assertThat(passed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void unknownResourceIsNotFoundBeforeMethodAndAccept() throws Exception {
        when(gateway.resolve(anyString(), anyInt())).thenReturn(Optional.empty());

        MockHttpServletResponse response = run("POST", "/api/v1/specifications", "text/html");

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(json(response)).containsEntry("code", "not_found");
        assertThat(response.getHeader(HttpHeaders.ALLOW)).isNull();
    }

    @Test
    void nonCanonicalTargetsAreNotFoundWithoutResolving() throws Exception {
        for (String uri : new String[]{"/api/v01/specifications", "/api/v1x/specifications",
                "/api/v0/specifications", "/api/v1/", "/api/v1/specifications/",
                "/api/v1/specifications/42/extra", "/api/v1/Specifications", "/api/v1/specs!"}) {
            MockHttpServletResponse response = run("GET", uri, null);
            assertThat(response.getStatus()).as(uri).isEqualTo(404);
        }

        verify(gateway, never()).resolve(anyString(), anyInt());
    }

    @Test
    void nonGetMethodOfAPublishedTargetIsMethodNotAllowed() throws Exception {
        known();

        for (String method : new String[]{"POST", "PUT", "HEAD", "OPTIONS", "DELETE"}) {
            MockHttpServletResponse response = run(method, "/api/v1/specifications", null);
            assertThat(response.getStatus()).as(method).isEqualTo(405);
            assertThat(response.getHeader(HttpHeaders.ALLOW)).as(method).isEqualTo("GET");
            assertThat(json(response)).containsEntry("code", "method_not_allowed");
        }
    }

    @Test
    void methodIsNotCheckedBeforeResourceExistence() throws Exception {
        when(gateway.resolve(anyString(), anyInt())).thenReturn(Optional.empty());

        MockHttpServletResponse response = run("POST", "/api/v1/anything", null);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void incompatibleAcceptIsNotAcceptable() throws Exception {
        known();

        MockHttpServletResponse response = run("GET", "/api/v1/specifications", "text/html");

        assertThat(response.getStatus()).isEqualTo(406);
        assertThat(json(response)).containsEntry("code", "not_acceptable");
    }

    @Test
    void jsonCompatibleAcceptFormsPass() throws Exception {
        known();

        for (String accept : new String[]{null, "*/*", "application/*", "application/json",
                "application/json;charset=UTF-8", "text/html, application/json"}) {
            MockHttpServletResponse response = run("GET", "/api/v1/specifications", accept);
            assertThat(response.getStatus()).as(String.valueOf(accept)).isEqualTo(200);
            assertThat(passed).isTrue();
        }
    }

    @Test
    void detailFormIsRoutedSeparatelyFromList() throws Exception {
        when(gateway.resolve("specifications", 1)).thenReturn(Optional.of(mock(RestReadResourceSchema.class)));

        MockHttpServletResponse response = run("GET", "/api/v1/specifications/1001", null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(passed).isTrue();
        verify(gateway).resolve("specifications", 1);
    }

    @Test
    void errorResponsesAlsoCarryNoStore() throws Exception {
        known();

        MockHttpServletResponse response = run("POST", "/api/v1/specifications", null);

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    private boolean passed;

    @Test
    void qualityAndSpecificityControlJsonAcceptability() throws Exception {
        known();
        for (String accept : new String[]{"application/json;q=0", "*/*;q=0",
                "application/json;q=0, */*;q=1", "application/*;q=0, */*;q=1",
                "application/json;charset=UTF-8;q=0, application/json;q=1",
                "application/json;q=2", "application/json;q=-1", "application/json;q=abc",
                "application/json;q=NaN", "application/*+json"}) {
            assertThat(run("GET", "/api/v1/specifications", accept).getStatus())
                .as(accept).isEqualTo(406);
        }
        for (String accept : new String[]{"application/json;q=0.1",
                "application/json;q=1, */*;q=0", "application/*;q=0.5, */*;q=0",
                "application/json;q=0.2, application/*;q=0"}) {
            assertThat(run("GET", "/api/v1/specifications", accept).getStatus())
                .as(accept).isEqualTo(200);
        }
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT, "*/*;q=1");
        request.addHeader(HttpHeaders.ACCEPT, "application/json;q=0");
        assertThat(RestReadNamespaceFilter.acceptsJson(request)).isFalse();
    }

    @Test
    void contextPathIsRemovedWithoutDecodingTheRemainingPath() throws Exception {
        known();
        for (String path : new String[]{"/api/v1/specifications", "/api/v1/specifications/42"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app" + path);
            request.setContextPath("/app");
            AtomicBoolean continued = new AtomicBoolean();
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> continued.set(true));
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(continued).isTrue();
        }
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/api/v1/Specifications");
        request.setContextPath("/app");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("Noncanonical path"); });
        assertThat(response.getStatus()).isEqualTo(404);
    }

    private MockHttpServletResponse run(String method, String uri, String accept) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        if (accept != null) {
            request.addHeader(HttpHeaders.ACCEPT, accept);
        }
        passed = false;
        AtomicBoolean reached = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> reached.set(true);
        filter.doFilter(request, response, chain);
        passed = reached.get();
        return response;
    }

    private void known() {
        when(gateway.resolve(anyString(), anyInt())).thenReturn(Optional.of(mock(RestReadResourceSchema.class)));
    }

    private static Map<String, Object> json(MockHttpServletResponse response) throws Exception {
        return JSON.readValue(response.getContentAsByteArray(), new TypeReference<Map<String, Object>>() { });
    }
}
