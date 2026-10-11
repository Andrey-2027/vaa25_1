package org.ipro.rest.web.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import org.hibernate.Session;
import org.ipro.data.EntityReadAccess;
import org.ipro.rest.api.RestResources;
import org.ipro.rest.catalog.*;
import org.ipro.rest.gateway.DefaultRestReadGateway;
import org.ipro.rest.gateway.RestReadGateway;
import org.ipro.rest.security.RestReferenceAuthorizationValidator;
import org.ipro.rest.service.RestReadService;
import org.ipro.rest.web.mvc.RestReadController;
import org.ipro.rest.web.codec.RestJsonCodec;
import org.ipro.rest.web.problem.RestReadExceptionHandler;
import org.ipro.rest.web.rls.RestAwareRlsReadableIdsResolver;
import org.ipro.rest.web.rls.RestRequestContext;
import org.ipro.rls.*;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ipro.rest.api.RestFieldType.LONG;
import static org.ipro.rest.api.RestNullability.NOT_NULL;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Basic, gateway, read service, scoped resolver/cache and RLS activator; storage is substituted. */
class RestReadIdentityRlsIntegrationTest {
    private final Map<String, List<Long>> grants = new ConcurrentHashMap<>(Map.of(
        "alice", List.of(1L), "bob", List.of(2L)));
    private final AtomicBoolean apiPermitted = new AtomicBoolean(true);
    private final AtomicBoolean storageFailure = new AtomicBoolean(false);
    private final AtomicReference<RuntimeException> gateFailure = new AtomicReference<>();
    private final AccessService access = mock(AccessService.class);
    private final RlsReadableIdsCache uiCache = mock(RlsReadableIdsCache.class);
    private final C5PermissionEvaluator permissions = mock(C5PermissionEvaluator.class);
    private final EntityReadAccess storage = mock(EntityReadAccess.class);
    private final RlsCurrentUser identity = () -> SecurityContextHolder.getContext().getAuthentication() == null
        ? "anonymous" : SecurityContextHolder.getContext().getAuthentication().getName();

    @Test
    void subjectsGrantsAndRevocationAreEvaluatedAfreshWithoutSession() {
        runner().run(context -> {
            MockMvc mvc = mvc(context);
            assertRow(read(mvc, "alice", "/api/v1/items"), 1);
            assertRow(read(mvc, "bob", "/api/v1/items"), 2);
            grants.put("alice", List.of(3L));
            AccessGrantVersion.bump();
            assertRow(read(mvc, "alice", "/api/v1/items"), 3);
            verify(access, times(2)).getReadableIds("JOURNAL", "alice");
            verify(access).getReadableIds("JOURNAL", "bob");
            verify(permissions, atLeastOnce()).isApiPermitted("alice", "items", 1, "LIST");
            verify(permissions, atLeastOnce()).isApiPermitted("bob", "items", 1, "LIST");
            apiPermitted.set(false);
            mvc.perform(get("/api/v1/items").param("size", "bad").header("Authorization", basic("alice")))
                .andExpect(status().isForbidden());
            apiPermitted.set(true);
            assertRow(read(mvc, "bob", "/api/v1/items"), 2);
            when(permissions.isEntityReadPermitted("alice", Item.class)).thenReturn(false);
            mvc.perform(get("/api/v1/items").header("Authorization", basic("alice")))
                .andExpect(status().isForbidden());
            assertRow(read(mvc, "bob", "/api/v1/items"), 2);
            verifyNoInteractions(uiCache);
            assertClean();
        });
    }

    @Test
    void parallelSubjectsDoNotShareRequestCacheOrSecurityContext() {
        runner().run(context -> {
            MockMvc mvc = mvc(context);
            CyclicBarrier overlap = new CyclicBarrier(2);
            when(access.getReadableIds(anyString(), anyString())).thenAnswer(invocation -> {
                overlap.await(10, TimeUnit.SECONDS);
                return grants.get(invocation.getArgument(1));
            });
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<?> alice = pool.submit(() -> { assertRow(readUnchecked(mvc, "alice"), 1); assertClean(); });
                Future<?> bob = pool.submit(() -> { assertRow(readUnchecked(mvc, "bob"), 2); assertClean(); });
                alice.get(15, TimeUnit.SECONDS);
                bob.get(15, TimeUnit.SECONDS);
            }
            verify(access).getReadableIds("JOURNAL", "alice");
            verify(access).getReadableIds("JOURNAL", "bob");
            verifyNoInteractions(uiCache);
        });
    }

    @Test
    void initialC5FaultIsTypedBeforeParserAndReadAndDoesNotPoisonTheNextRequest() {
        runner().run(context -> {
            MockMvc mvc = mvc(context);
            for (RuntimeException failure : List.of(
                    new org.ipro.rls.c5.C5PermissionConfigurationException("secret grant store details"),
                    new org.springframework.dao.DataAccessResourceFailureException("secret grant store details"),
                    new IllegalStateException("secret programmer error"))) {
                gateFailure.set(failure);
                MvcResult result = mvc.perform(get("/api/v1/items").header("Authorization", basic("alice"))
                    .param("size", "invalid-secret-size")).andReturn();
                int expected = failure instanceof IllegalStateException
                    && !(failure instanceof org.ipro.rls.c5.C5PermissionConfigurationException) ? 500 : 503;
                assertThat(result.getResponse().getStatus()).isEqualTo(expected);
                assertThat(result.getResponse().getContentAsString())
                    .contains(expected == 503 ? "security_unavailable" : "internal_error")
                    .doesNotContain("secret grant", "secret programmer", "invalid-secret-size");
                assertThat(result.getRequest().getSession(false)).isNull();
                assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
                assertClean();
            }
            verifyNoInteractions(storage);
            gateFailure.set(null);
            assertRow(read(mvc, "alice", "/api/v1/items"), 1);
        });
    }

    @Test
    void absentAndRlsHiddenDetailsHaveTheSameProblemShape() {
        runner().run(context -> {
            MockMvc mvc = mvc(context);
            MvcResult absent = mvc.perform(get("/api/v1/items/999").header("Authorization", basic("alice")))
                .andExpect(status().isNotFound()).andReturn();
            MvcResult hidden = mvc.perform(get("/api/v1/items/1").header("Authorization", basic("bob")))
                .andExpect(status().isNotFound()).andReturn();
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            Map<String, Object> absentBody = json.readValue(absent.getResponse().getContentAsByteArray(),
                new tools.jackson.core.type.TypeReference<>() { });
            Map<String, Object> hiddenBody = json.readValue(hidden.getResponse().getContentAsByteArray(),
                new tools.jackson.core.type.TypeReference<>() { });
            absentBody.remove("traceId"); absentBody.remove("instance");
            hiddenBody.remove("traceId"); hiddenBody.remove("instance");
            assertThat(absentBody).isEqualTo(hiddenBody).containsEntry("code", "not_found");
            assertClean();
        });
    }

    @Test
    void contextPathDetailErrorsAndOtherChainsRemainIsolated() {
        runner().run(context -> {
            MockMvc mvc = mvc(context);
            mvc.perform(get("/app/api/v1/items/1").contextPath("/app")
                    .header("Authorization", basic("alice"))).andExpect(status().isOk());
            mvc.perform(get("/app/api/v1/items/1").contextPath("/app")
                    .header("Authorization", basic("bob"))).andExpect(status().isNotFound());
            mvc.perform(get("/app/api/v1/items").contextPath("/app"))
                .andExpect(status().isUnauthorized());
            mvc.perform(get("/app/api/v1/unknown").contextPath("/app")
                    .header("Authorization", basic("alice"))).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/items").header("Authorization", basic("alice"))
                    .header("Accept", "application/json;q=0, */*;q=1"))
                .andExpect(status().isNotAcceptable());
            mvc.perform(get("/api/v1/items").header("Authorization", basic("alice")).param("size", "bad"))
                .andExpect(status().isBadRequest());
            assertClean();
            assertRow(read(mvc, "alice", "/api/v1/items"), 1);
            storageFailure.set(true);
            mvc.perform(get("/api/v1/items").header("Authorization", basic("alice")))
                .andExpect(status().isInternalServerError());
            storageFailure.set(false);
            assertClean();
            assertRow(read(mvc, "bob", "/api/v1/items"), 2);
            mvc.perform(get("/login")).andExpect(status().is(404))
                .andExpect(result -> assertThat(result.getResponse().getHeader("X-Test-Chain")).isEqualTo("UI"));
            mvc.perform(get("/api/report-jpql/preview")).andExpect(status().is(404))
                .andExpect(result -> assertThat(result.getResponse().getHeader("X-Test-Chain")).isEqualTo("JPQL"));
            verifyNoInteractions(uiCache);
            assertClean();
        });
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    private WebApplicationContextRunner runner() {
        var definition = RestResources.publish("items", 1, Item.class)
            .field("id", LONG, RestResources.path("id"), NOT_NULL)
            .listFields("id").listDefaultFields("id")
            .detailFields("id").detailDefaultFields("id").sortBy("id").build();
        var path = new ResolvedRestPath("id", List.of(new ResolvedRestPath.Segment(
            "id", Item.class, Long.class, Attribute.PersistentAttributeType.BASIC, false, false)), Long.class, false);
        var field = new ResolvedRestField(definition.fields().get("id"), path);
        var resource = mock(ResolvedRestResource.class);
        doReturn(definition).when(resource).definition();
        when(resource.key()).thenReturn(new RestResourceKey("items", 1));
        when(resource.fields()).thenReturn(Map.of("id", field));
        when(resource.sorts()).thenReturn(Map.of("id", path));
        when(resource.projection(any())).thenAnswer(invocation -> new RestResourceProjection(
            invocation.getArgument(0), List.of("id"), List.of("id"), Map.of("id", field)));
        var catalog = mock(RestResourceCatalog.class);
        when(catalog.resources()).thenReturn(List.of(resource));
        when(catalog.find("items", 1)).thenReturn(Optional.of(resource));
        when(permissions.isApiPermitted(anyString(), anyString(), anyInt(), anyString()))
            .thenAnswer(invocation -> {
                if (gateFailure.get() != null) throw gateFailure.get();
                return apiPermitted.get();
            });
        when(permissions.isEntityReadPermitted(anyString(), any())).thenReturn(true);
        when(permissions.isAttributeReadPermitted(anyString(), any(), anyString())).thenReturn(true);
        when(access.getReadableIds(anyString(), anyString())).thenAnswer(invocation -> grants.get(invocation.getArgument(1)));
        PasswordEncoder encoder = NoOpPasswordEncoder.getInstance();
        return new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestRlsResolverAutoConfiguration.class, RestWebAutoConfiguration.class))
            .withUserConfiguration(SecurityAndTransactions.class)
            .withPropertyValues("platform.rest.web.enabled=true", "platform.rest.read.enabled=true")
            .withBean(AccessService.class, () -> access)
            .withBean(RlsCurrentUser.class, () -> identity)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(RestJsonCodec.class, () -> {
                RestJsonCodec codec = spy(new RestJsonCodec(JsonMapper.builder().build()));
                doAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return invocation.callRealMethod();
                }).when(codec).writeList(any());
                doAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return invocation.callRealMethod();
                }).when(codec).writeDetail(any());
                return codec;
            })
            .withBean(UserDetailsService.class, () -> new InMemoryUserDetailsManager(
                User.withUsername("alice").password("secret").roles("USER").build(),
                User.withUsername("bob").password("secret").roles("USER").build()))
            .withBean(PasswordEncoder.class, () -> encoder)
            .withBean(RestResourceCatalog.class, () -> catalog)
            .withBean(RestReadIdentityRlsIntegrationTest.class, () -> this);
    }

    private RestReadGateway gateway(RestResourceCatalog catalog, RestAwareRlsReadableIdsResolver resolver) {
        var registry = mock(RlsDimensionRegistry.class);
        when(registry.dimensions()).thenReturn(Set.of("JOURNAL"));
        when(registry.kindOf("JOURNAL")).thenReturn(RlsDimensionKind.FILTERABLE);
        var activator = new RlsFilterActivator(registry, resolver, identity);
        when(storage.list(any(), any(), any(), any())).thenAnswer(invocation -> {
            List<Long> ids = activate(activator);
            if (storageFailure.get()) throw new IllegalStateException("storage failure");
            return new PageImpl<>(ids.stream().map(Item::new).toList(),
                invocation.getArgument(2, Pageable.class), ids.size());
        });
        when(storage.detail(any(), any(), any())).thenAnswer(invocation -> {
            List<Long> ids = activate(activator);
            Long id = invocation.getArgument(1);
            return ids.contains(id) ? Optional.of(new Item(id)) : Optional.empty();
        });
        var service = new RestReadService(catalog, storage, permissions, identity,
            mock(RestReferenceAuthorizationValidator.class));
        return new DefaultRestReadGateway(catalog, service, identity, permissions, 10_000);
    }

    private static List<Long> activate(RlsFilterActivator activator) {
        assertThat(RestRequestContext.isActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
        EntityManager em = mock(EntityManager.class);
        Session session = mock(Session.class);
        org.hibernate.Filter filter = mock(org.hibernate.Filter.class);
        Map<String, Object> properties = new HashMap<>();
        AtomicReference<List<Long>> applied = new AtomicReference<>();
        when(em.unwrap(Session.class)).thenReturn(session);
        when(em.getProperties()).thenReturn(properties);
        doAnswer(invocation -> { properties.put(invocation.getArgument(0), invocation.getArgument(1)); return null; })
            .when(em).setProperty(anyString(), any());
        when(session.enableFilter("JOURNAL")).thenReturn(filter);
        when(filter.setParameterList(eq("allowedIds"), anyCollection())).thenAnswer(invocation -> {
            applied.set(List.copyOf(invocation.getArgument(1))); return filter;
        });
        try {
            activator.ensureRlsEnabled(em);
            // The request cache must also serve another session inside the same HTTP request.
            properties.clear();
            activator.ensureRlsEnabled(em);
            return applied.get();
        } finally {
            RlsStatementGuard.clearSession();
        }
    }

    private static MockMvc mvc(org.springframework.context.ApplicationContext context) {
        return MockMvcBuilders.standaloneSetup(context.getBean(RestReadController.class))
            .setControllerAdvice(context.getBean(RestReadExceptionHandler.class))
            .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
    }
    private static MvcResult read(MockMvc mvc, String user, String path) throws Exception {
        MvcResult result = mvc.perform(get(path).header("Authorization", basic(user)))
            .andExpect(status().isOk()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        return result;
    }
    private static MvcResult readUnchecked(MockMvc mvc, String user) {
        try { return read(mvc, user, "/api/v1/items"); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static void assertRow(MvcResult result, int id) {
        assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
            .contains("\"content\":[{\"id\":" + id + "}]");
    }
    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":secret").getBytes(StandardCharsets.UTF_8));
    }
    private static void assertClean() {
        assertThat(RestRequestContext.isActive()).isFalse();
        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
    public static class Item {
        private final Long id;
        Item(Long id) { this.id = id; }
        public Long getId() { return id; }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableTransactionManagement(proxyTargetClass = true)
    static class SecurityAndTransactions {
        @Bean
        @org.springframework.web.context.annotation.SessionScope
        RlsReadableIdsCache uiCache(RestReadIdentityRlsIntegrationTest fixture) { return fixture.uiCache; }
        @Bean
        RestReadGateway gateway(RestReadIdentityRlsIntegrationTest fixture, RestResourceCatalog catalog,
                                RestAwareRlsReadableIdsResolver resolver) {
            return fixture.gateway(catalog, resolver);
        }
        @Bean @Order(1)
        SecurityFilterChain jpql(HttpSecurity http) throws Exception { return fallback(http, "/api/report-jpql/**", "JPQL"); }
        @Bean @Order(3)
        SecurityFilterChain ui(HttpSecurity http) throws Exception { return fallback(http, "/**", "UI"); }
        private SecurityFilterChain fallback(HttpSecurity http, String matcher, String name) throws Exception {
            return http.securityMatcher(matcher).authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore((request, response, chain) -> {
                    assertThat(RestRequestContext.isActive()).isFalse();
                    ((jakarta.servlet.http.HttpServletResponse) response).setHeader("X-Test-Chain", name);
                    chain.doFilter(request, response);
                }, org.springframework.security.web.context.SecurityContextHolderFilter.class).build();
        }
        @Bean AbstractPlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
                @Override protected void doCommit(DefaultTransactionStatus status) { }
                @Override protected void doRollback(DefaultTransactionStatus status) { }
            };
        }
    }
}
