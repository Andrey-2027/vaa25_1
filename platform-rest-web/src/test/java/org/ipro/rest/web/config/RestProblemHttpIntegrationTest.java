package org.ipro.rest.web.config;

import org.ipro.rest.gateway.RestReadGateway;
import org.ipro.rest.gateway.RestReadResourceSchema;
import org.ipro.rest.gateway.RestListContract;
import org.ipro.rest.gateway.RestFieldFact;
import org.ipro.rest.service.RestReadException;
import org.ipro.rest.service.RestReadOutcome;
import org.ipro.rest.service.RestPageResult;
import org.ipro.rest.web.codec.RestJsonCodec;
import org.ipro.rest.web.problem.RestReadExceptionHandler;
import org.ipro.rest.web.rls.RestRequestContext;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ipro.rest.api.RestFieldFormat.NONE;
import static org.ipro.rest.api.RestFieldType.LONG;
import static org.ipro.rest.api.RestNullability.NOT_NULL;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Managed MVC + Security context: faults before authentication, without a handler, and in read/codec. */
class RestProblemHttpIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SECRET = "secret SQL org.ip.internal.Store REST:items:v1:LIST";
    private final RestReadGateway gateway = mock(RestReadGateway.class);
    private final RestJsonCodec codec = spy(new RestJsonCodec(JSON));
    private final AtomicReference<RuntimeException> readFailure = new AtomicReference<>();
    private final AtomicReference<Exception> frameworkFailure = new AtomicReference<>();

    @Test
    void securityNamespaceAndReadFailuresHaveOneProblemContract() {
        runner().run(context -> {
            MockMvc mvc = mvc(context);
            problem(mvc, get("/api/v1/items").param("size", "bad"), 401, "unauthenticated");
            problem(mvc, get("/api/v1/unknown"), 401, "unauthenticated");
            problem(mvc, authenticated(get("/api/v1/unknown")), 404, "not_found");
            problem(mvc, authenticated(get("/api/v999/items")), 404, "not_found");
            problem(mvc, authenticated(post("/api/v1/items")).header("Accept", "text/html"), 405, "method_not_allowed");
            problem(mvc, authenticated(get("/api/v1/items")).header("Accept", "text/html"), 406, "not_acceptable");
            for (var outcome : List.of(RestReadOutcome.UNAUTHENTICATED, RestReadOutcome.FORBIDDEN,
                    RestReadOutcome.INVALID_REQUEST, RestReadOutcome.RESOURCE_NOT_FOUND,
                    RestReadOutcome.DETAIL_NOT_FOUND, RestReadOutcome.SECURITY_UNAVAILABLE)) {
                readFailure.set(new RestReadException(outcome, SECRET));
                int expected = switch (outcome) {
                    case UNAUTHENTICATED -> 401; case FORBIDDEN -> 403; case INVALID_REQUEST -> 400;
                    case RESOURCE_NOT_FOUND, DETAIL_NOT_FOUND -> 404; default -> 503;
                };
                String code = switch (expected) {
                    case 401 -> "unauthenticated"; case 403 -> "forbidden"; case 400 -> "invalid_request";
                    case 404 -> "not_found"; default -> "security_unavailable";
                };
                problem(mvc, authenticated(get("/api/v1/items")), expected, code);
            }
            readFailure.set(new DataAccessResourceFailureException(SECRET));
            problem(mvc, authenticated(get("/api/v1/items")), 503, "security_unavailable");
            readFailure.set(new IllegalArgumentException(SECRET));
            problem(mvc, authenticated(get("/api/v1/items")), 500, "internal_error");
            readFailure.set(null);
            Map<String, Object> parserProblem = problem(mvc, authenticated(get("/api/v1/items"))
                .param("size", SECRET), 400, "invalid_request");
            assertThat(parserProblem).containsEntry("param", "size").containsEntry("reason", "invalid_size");
        });
    }

    @Test
    void authenticationOutageAndUnexpectedProviderFaultAreNotBadCredentials() {
        runner().run(context -> {
            MockMvc mvc = mvc(context);
            problem(mvc, get("/api/v1/items").header("Authorization", basic("wrong")), 401, "unauthenticated");
            problem(mvc, get("/api/v1/items").header("Authorization", basic("provider-down")), 503, "security_unavailable");
            problem(mvc, get("/api/v1/items").header("Authorization", basic("provider-resource-down")), 503, "security_unavailable");
            problem(mvc, get("/api/v1/items").header("Authorization", basic("provider-crash")), 500, "internal_error");
            verify(gateway, never()).list(anyString(), anyInt(), any());
        });
    }

    @Test
    void frameworkErrorsWithoutAHandlerAreNormalizedOnlyForMarkedRestRequests() {
        runner().run(context -> {
            MockMvc mvc = mvc(context);
            for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND,
                    HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_ACCEPTABLE, HttpStatus.INTERNAL_SERVER_ERROR)) {
                frameworkFailure.set(new ResponseStatusException(status, SECRET));
                String code = switch (status) {
                    case BAD_REQUEST -> "invalid_request"; case NOT_FOUND -> "not_found";
                    case METHOD_NOT_ALLOWED -> "method_not_allowed"; case NOT_ACCEPTABLE -> "not_acceptable";
                    default -> "internal_error";
                };
                problem(mvc, authenticated(get("/api/v1/items")), status.value(), code);
            }
            frameworkFailure.set(null);
            MvcResult outside = mvc.perform(get("/outside")).andReturn();
            assertThat(outside.getResponse().getStatus()).isEqualTo(404);
            assertThat(outside.getResponse().getContentAsString()).doesNotContain("internal_error", "traceId");
            // Handler-null errors outside REST must remain available to the application's resolvers.
            var resolver = context.getBean(RestReadExceptionHandler.class);
            assertThat(resolver.resolveException(new org.springframework.mock.web.MockHttpServletRequest("GET", "/outside"),
                new org.springframework.mock.web.MockHttpServletResponse(), null,
                new ResponseStatusException(HttpStatus.NOT_FOUND, SECRET))).isNull();
        });
    }

    @Test
    void serializationFailuresNeverEmitPartialSuccessfulJsonEvenWhenProblemCodecAlsoFails() {
        runner().run(context -> {
            MockMvc mvc = mvc(context);
            doThrow(new IllegalStateException(SECRET)).when(codec).writeList(any());
            problem(mvc, authenticated(get("/api/v1/items")), 500, "internal_error");
            doThrow(new IllegalStateException(SECRET)).when(codec).writeProblem(any());
            problem(mvc, authenticated(get("/api/v1/items")), 500, "internal_error");
        });
    }

    private WebApplicationContextRunner runner() {
        RestFieldFact id = new RestFieldFact("id", LONG, NONE, NOT_NULL);
        var list = new RestListContract("items", 1, List.of(id), List.of(id), Map.of(),
            List.of("id"), null, null, 50, 200, 10_000);
        when(gateway.resolve("items", 1)).thenReturn(Optional.of(mock(RestReadResourceSchema.class)));
        when(gateway.list(eq("items"), eq(1), any())).thenAnswer(invocation -> {
            if (readFailure.get() != null) throw readFailure.get();
            ((org.ipro.rest.gateway.RestListRequestFactory) invocation.getArgument(2)).create(list);
            return new RestPageResult(List.of(Map.of("id", 1L)), 0, 50, 1, 1);
        });
        return new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestRlsResolverAutoConfiguration.class, RestWebAutoConfiguration.class))
            .withUserConfiguration(MvcSecurityConfiguration.class)
            .withPropertyValues("platform.rest.web.enabled=true", "platform.rest.read.enabled=true")
            .withBean(RestProblemHttpIntegrationTest.class, () -> this)
            .withBean(RestReadGateway.class, () -> gateway)
            .withBean(RestJsonCodec.class, () -> codec)
            .withBean(JsonMapper.class, () -> JSON)
            .withBean(AccessService.class, () -> mock(AccessService.class))
            .withBean(RlsCurrentUser.class, () -> (RlsCurrentUser) () -> {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                return authentication == null ? "anonymous" : authentication.getName();
            });
    }
    private static MockMvc mvc(org.springframework.context.ApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
            .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
    }
    private static Map<String, Object> problem(MockMvc mvc, MockHttpServletRequestBuilder request,
                                              int status, String code) throws Exception {
        MvcResult result = mvc.perform(request.header("X-Requested-With", "XMLHttpRequest")
            .header("X-Trace-Id", "client-trace-secret")).andReturn();
        var response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
            .isEqualTo(status == 401 ? "Basic realm=\"rest-read\"" : null);
        assertThat(response.getHeader(HttpHeaders.ALLOW)).isEqualTo(status == 405 ? "GET" : null);
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(result.getRequest().getSession(false)).isNull();
        Map<String, Object> body = JSON.readValue(response.getContentAsByteArray(), new TypeReference<>() { });
        assertThat(body).containsEntry("type", "about:blank").containsEntry("status", status).containsEntry("code", code);
        assertThat(body.get("instance")).isEqualTo(result.getRequest().getRequestURI());
        assertThat(UUID.fromString((String) body.get("traceId"))).isNotNull();
        assertThat(response.getContentAsString()).doesNotContain(SECRET, "client-trace-secret", "\"content\":", "stackTrace");
        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(RestRequestContext.isActive()).isFalse();
        return body;
    }
    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", basic("alice"));
    }
    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":secret").getBytes(StandardCharsets.UTF_8));
    }
    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class MvcSecurityConfiguration {
        @Bean OutsideController outsideController() { return new OutsideController(); }
        @Bean AuthenticationProvider credentials() {
            return new AuthenticationProvider() {
                @Override public Authentication authenticate(Authentication authentication) {
                    return switch (authentication.getName()) {
                        case "provider-down" -> throw new AuthenticationServiceException(SECRET, new DataAccessResourceFailureException(SECRET));
                        case "provider-resource-down" -> throw new DataAccessResourceFailureException(SECRET);
                        case "provider-crash" -> throw new IllegalStateException(SECRET);
                        case "alice" -> UsernamePasswordAuthenticationToken.authenticated("alice", null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER")));
                        default -> throw new BadCredentialsException(SECRET);
                    };
                }
                @Override public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
            };
        }
        @Bean HandlerMapping frameworkFaultMapping(RestProblemHttpIntegrationTest fixture) {
            return new AbstractHandlerMapping() {
                { setOrder(-1); }
                @Override protected Object getHandlerInternal(jakarta.servlet.http.HttpServletRequest request) throws Exception {
                    if (request.getRequestURI().startsWith("/api/") && fixture.frameworkFailure.get() != null)
                        throw fixture.frameworkFailure.get();
                    return null;
                }
            };
        }
    }
    @RestController
    static class OutsideController {
        @GetMapping("/outside") String outside() { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    }
}
