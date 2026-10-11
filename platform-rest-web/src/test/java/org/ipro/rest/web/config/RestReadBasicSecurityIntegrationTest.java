package org.ipro.rest.web.config;

import org.ipro.rest.api.RestSortDirection;
import org.ipro.rest.gateway.RestFieldFact;
import org.ipro.rest.gateway.RestListContract;
import org.ipro.rest.gateway.RestListRequestFactory;
import org.ipro.rest.gateway.RestReadGateway;
import org.ipro.rest.gateway.RestReadResourceSchema;
import org.ipro.rest.service.RestListQuery;
import org.ipro.rest.service.RestPageResult;
import org.ipro.rest.service.RestReadException;
import org.ipro.rest.service.RestReadOutcome;
import org.ipro.rest.service.RestReadService;
import org.ipro.rest.web.mvc.RestReadController;
import org.ipro.rest.web.problem.RestReadExceptionHandler;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ipro.rest.api.RestFieldFormat.NONE;
import static org.ipro.rest.api.RestFieldType.LONG;
import static org.ipro.rest.api.RestFieldType.STRING;
import static org.ipro.rest.api.RestNullability.NOT_NULL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-REST-READ-4 §5/§6 on the real chain: in-memory credentials through the actual stateless Basic
 * filter chain (built by {@code RestWebAutoConfiguration}), not a mock user. Asserts 401 for a
 * missing/wrong/disabled credential with the Basic challenge, no session and no
 * {@code Set-Cookie: JSESSIONID} on either success or failure, 403 for an authenticated subject
 * the read pipeline denies (no challenge on 403), and 405 after authentication.
 */
class RestReadBasicSecurityIntegrationTest {

    private static final RestFieldFact ID = new RestFieldFact("id", LONG, NONE, NOT_NULL);
    private static final RestFieldFact CODE_SPEC = new RestFieldFact("codeSpec", STRING, NONE, NOT_NULL);
    private static final RestListContract LIST = new RestListContract(
        "specifications", 1, List.of(ID, CODE_SPEC), List.of(ID, CODE_SPEC), Map.of(),
        List.of("id"), "id", RestSortDirection.ASC, 50, 200, 10_000);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RestReadGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = mock(RestReadGateway.class);
        when(gateway.resolve(eq("specifications"), eq(1)))
            .thenReturn(Optional.of(mock(RestReadResourceSchema.class)));
        when(gateway.list(eq("specifications"), eq(1), any(RestListRequestFactory.class)))
            .thenAnswer(invocation -> {
                RestListQuery query = ((RestListRequestFactory) invocation.getArgument(2)).create(LIST);
                int size = query.size() == 0 ? LIST.defaultPageSize() : query.size();
                return new RestPageResult(List.of(Map.of("id", 1001L, "codeSpec", "SP-001")),
                    query.page(), size, 1, 1);
            });
    }

    @Test
    void realCredentialsReachTheControllerWithoutASession() throws Exception {
        enabled().run(context -> {
            MvcResult result = mvc(context).perform(get("/api/v1/specifications")
                    .header(HttpHeaders.AUTHORIZATION, basic("alice", "secret")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                    org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();

            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
            assertThat(result.getRequest().getSession(false)).isNull();
            assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        });
    }

    @Test
    void anonymousRequestIsUnauthorizedWithTheChallenge() throws Exception {
        enabled().run(context -> {
            MvcResult result = mvc(context).perform(get("/api/v1/specifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"rest-read\""))
                .andReturn();

            assertThat(result.getResponse().getContentType()).isEqualTo("application/problem+json");
            assertThat(json(result)).containsEntry("code", "unauthenticated").containsEntry("status", 401);
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
            assertThat(result.getRequest().getSession(false)).isNull();
        });
    }

    @Test
    void wrongPasswordIsUnauthorized() throws Exception {
        enabled().run(context -> mvc(context).perform(get("/api/v1/specifications")
                .header(HttpHeaders.AUTHORIZATION, basic("alice", "wrong")))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"rest-read\"")));
    }

    @Test
    void disabledAccountIsUnauthorized() throws Exception {
        enabled().run(context -> mvc(context).perform(get("/api/v1/specifications")
                .header(HttpHeaders.AUTHORIZATION, basic("disabled", "secret")))
            .andExpect(status().isUnauthorized()));
    }

    @Test
    void foreignOrStaleSessionCookieIsNotACredential() throws Exception {
        enabled().run(context -> {
            MvcResult result = mvc(context).perform(get("/api/v1/specifications")
                    .cookie(new jakarta.servlet.http.Cookie("JSESSIONID", "stale-session"))
                    .header(HttpHeaders.AUTHORIZATION, basic("alice", "secret")))
                .andExpect(status().isOk())
                .andReturn();

            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
            assertThat(result.getRequest().getSession(false)).isNull();
        });
    }

    @Test
    void existingUiSessionIsNeitherACredentialNorChangedByBasic() {
        enabled().run(context -> {
            var session = new org.springframework.mock.web.MockHttpSession();
            String originalId = session.getId();
            var uiContext = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            uiContext.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("ui-user", "n/a", List.of()));
            String key = org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
            session.setAttribute(key, uiContext);
            mvc(context).perform(get("/api/v1/specifications").session(session))
                .andExpect(status().isUnauthorized());
            mvc(context).perform(get("/api/v1/specifications").session(session)
                    .header(HttpHeaders.AUTHORIZATION, basic("alice", "secret")))
                .andExpect(status().isOk()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
            assertThat(session.getId()).isEqualTo(originalId);
            assertThat(session.getAttribute(key)).isSameAs(uiContext);
        });
    }

    @Test
    void repeatedCredentialsWithoutAnyCookieKeepWorking() throws Exception {
        enabled().run(context -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                MvcResult result = mvc(context).perform(get("/api/v1/specifications")
                        .header(HttpHeaders.AUTHORIZATION, basic("alice", "secret")))
                    .andExpect(status().isOk())
                    .andReturn();
                assertThat(result.getRequest().getSession(false)).isNull();
            }
        });
    }

    @Test
    void authenticatedButDeniedSubjectIsForbiddenWithoutASession() throws Exception {
        when(gateway.list(eq("specifications"), eq(1), any(RestListRequestFactory.class)))
            .thenThrow(new RestReadException(RestReadOutcome.FORBIDDEN, "denied"));

        enabled().run(context -> {
            MvcResult result = mvc(context).perform(get("/api/v1/specifications")
                    .header(HttpHeaders.AUTHORIZATION, basic("alice", "secret")))
                .andExpect(status().isForbidden())
                .andReturn();

            assertThat(json(result)).containsEntry("code", "forbidden").containsEntry("status", 403);
            assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
            assertThat(result.getRequest().getSession(false)).isNull();
        });
    }

    @Test
    void unsupportedMethodIsRejectedAfterAuthentication() throws Exception {
        enabled().run(context -> {
            MvcResult result = mvc(context).perform(post("/api/v1/specifications")
                    .header(HttpHeaders.AUTHORIZATION, basic("alice", "secret")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
                .andReturn();

            assertThat(json(result)).containsEntry("code", "method_not_allowed");
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        });
    }

    private MockMvc mvc(org.springframework.context.ApplicationContext context) {
        FilterChainProxy security = new FilterChainProxy(
            context.getBean(SecurityFilterChain.class));
        return MockMvcBuilders
            .standaloneSetup(context.getBean(RestReadController.class))
            .setControllerAdvice(context.getBean(RestReadExceptionHandler.class))
            .addFilters(security)
            .build();
    }

    private WebApplicationContextRunner enabled() {
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        return new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestRlsResolverAutoConfiguration.class,
                RestWebAutoConfiguration.class))
            .withUserConfiguration(TestSecurityConfiguration.class)
            .withPropertyValues("platform.rest.web.enabled=true", "platform.rest.read.enabled=true")
            .withBean(RestReadService.class, () -> mock(RestReadService.class))
            .withBean(RestReadGateway.class, () -> gateway)
            .withBean(JsonMapper.class, () -> JSON)
            .withBean(AccessService.class, () -> mock(AccessService.class))
            .withBean(RlsCurrentUser.class, () -> (RlsCurrentUser) () -> "system")
            .withBean(UserDetailsService.class, () -> new InMemoryUserDetailsManager(
                User.withUsername("alice").password(encoder.encode("secret")).roles("USER").build(),
                User.withUsername("disabled").password(encoder.encode("secret")).disabled(true).build()))
            .withBean(PasswordEncoder.class, () -> encoder);
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> json(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsByteArray(),
            new TypeReference<Map<String, Object>>() { });
    }
}
