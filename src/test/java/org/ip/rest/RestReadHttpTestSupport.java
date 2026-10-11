package org.ip.rest;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.Filter;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.*;
import org.ip.repository.*;
import org.ipro.rest.api.*;
import org.ipro.rest.web.codec.RestJsonCodec;
import org.ipro.rest.web.rls.RestRequestContext;
import org.ipro.rls.*;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.telemetry.api.SqlStatementAudit;
import org.ipro.telemetry.core.SqlStatementAuditBridge;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Real socket → application credentials/C5/RLS/H2. Only the demo initializer is mocked.
 * The codec spy calls the real serializer, observing transaction/SQL boundaries only. */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"platform.rest.read.enabled=true", "platform.rest.web.enabled=true",
        "platform.rest.read.max-window=200", "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.datasource.url=jdbc:h2:mem:rest-http-46;DB_CLOSE_DELAY=-1",
        // Other suites contain subclasses inheriting @Route. Scan the actual login
        // view for this transport fixture; application security configuration is unchanged.
        "vaadin.allowed-packages=org.ip.views.login,com.vaadin",
        "vaadin.productionMode=true", "ip.test.isolation=rest-http-46"})
@Import(RestReadHttpTestSupport.ObservationConfiguration.class)
abstract class RestReadHttpTestSupport {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String PASSWORD = "http-pilot-secret";
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @MockitoBean DataInitializer dataInitializer;
    @MockitoSpyBean RestJsonCodec codec;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    /** Canonical write path: фикстура пишет governed-типы тем же каналом, что и приложение. */
    @Autowired org.ipro.data.EntityDataAccess access;
    @Autowired JournalRepository journals;
    @Autowired NomenclatureRepository nomenclatures;
    @Autowired PrdSpecRepository specifications;
    @Autowired UnitOfMeasurementRepository units;
    @Autowired AccessGrantRepository grants;
    @Autowired PasswordEncoder passwords;
    @Autowired ObservationStore observations;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    Fixture fixture;
    String alice, bob, denied, disabled;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void setupHttpFixtureAndSerializerObservation() {
        // Fixture repository reads use the ordinary UI session cache. This request
        // exists only on the JUnit setup thread, never on the real HTTP server threads.
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
            new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
        doAnswer(call -> {
            Observation observation = observations.current.get();
            if (observation != null) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(RlsContext.isBypassed()).isFalse();
                int before = observation.sql.size();
                Object wire = call.callRealMethod();
                assertThat(observation.sql.size()).as("SQL during serialization").isEqualTo(before);
                observation.serializations++;
                return wire;
            }
            return call.callRealMethod();
        }).when(codec).writeList(any());
        doAnswer(call -> {
            Observation observation = observations.current.get();
            if (observation != null) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                int before = observation.sql.size();
                Object wire = call.callRealMethod();
                assertThat(observation.sql.size()).as("SQL during serialization").isEqualTo(before);
                observation.serializations++;
                return wire;
            }
            return call.callRealMethod();
        }).when(codec).writeDetail(any());
        String token = UUID.randomUUID().toString().substring(0, 8);
        alice = "http-a-" + token; bob = "http-b-" + token;
        denied = "http-no-" + token; disabled = "http-off-" + token;
        fixture = RlsTestFixture.callAsSuperuser(grants, () -> {
            var unit = new UnitOfMeasurement(); unit.setCode("U" + token); unit.setShortCode("U" + token); unit.setName("Unit");
            unit = units.saveAndFlush(unit);
            var ja = new Journal(); ja.setCode("JA" + token); ja.setName("Journal A"); ja = journals.saveAndFlush(ja);
            var jb = new Journal(); jb.setCode("JB" + token); jb.setName("Journal B"); jb = journals.saveAndFlush(jb);
            var nom = new Nomenclature(); nom.setCode("N" + token); nom.setName("Name " + token);
            var group = new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                var value = new GroupNom("G" + token, "Group"); entityManager.persist(value); entityManager.flush(); return value;
            });
            nom.setGroupNom(group);
            nom.setTypeNom("T" + token); nom.setUnitOfMeasurement(unit); nom = nomenclatures.saveAndFlush(nom);
            var sa = new PrdSpec(); sa.setCodeSpec("SAME"); sa.setJournal(ja); sa.setNomenclature(nom); sa = specifications.saveAndFlush(sa);
            var sb = new PrdSpec(); sb.setCodeSpec("SAME"); sb.setJournal(jb); sb.setNomenclature(nom); sb = specifications.saveAndFlush(sb);
            for (String username : List.of(alice, bob, denied, disabled)) {
                var user = new User(username, passwords.encode(PASSWORD)); user.setEnabled(!username.equals(disabled));
                // Права субъекта выданы фикстурой выше (операции — на каждое определение): запись идёт canonical path.
                access.create(User.class, user);
            }
            return new Fixture(ja.getId(), jb.getId(), nom.getId(), nom.getCode(), nom.getName(), nom.getTypeNom(), sa.getId(), sb.getId(), unit.getId());
        });
        grant(AccessGrant.SubjectType.USER, alice, allReadKeys());
        grant(AccessGrant.SubjectType.USER, bob, allReadKeys());
        rowGrant(AccessGrant.SubjectType.USER, alice, fixture.journalA());
        rowGrant(AccessGrant.SubjectType.USER, bob, fixture.journalB());
        SecurityContextHolder.clearContext();
    }

    @org.junit.jupiter.api.AfterEach
    void clearFixtureRequest() {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) servlet.requestCompleted();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    Reply get(String user, String path) throws Exception { return request(user, PASSWORD, "GET", path, Map.of(), null); }
    Reply request(String user, String password, String method, String path, Map<String, String> headers, String payload) throws Exception {
        String id = UUID.randomUUID().toString();
        Observation observation = new Observation(); observations.byRequest.put(id, observation);
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(30)).header("X-Rest-Pilot-Request", id);
        if (user != null) builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        headers.forEach(builder::header);
        builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(observation.completed.await(10, TimeUnit.SECONDS)).as("server filter completed").isTrue();
            if (path.startsWith("/api/v")) {
                assertThat(response.headers().allValues("Set-Cookie")).as(observation.sessionMutations.toString())
                    .noneMatch(cookie -> cookie.contains("JSESSIONID"));
                assertThat(observation.sessionMutations).as("REST must not rotate an incoming session").isEmpty();
                assertThat(observation.securityClean && observation.markerClean && observation.requestClean).as("server thread cleanup").isTrue();
                if (!headers.containsKey("Cookie")) assertThat(observation.session).as("REST must not create a session").isFalse();
            }
            return new Reply(response, observation);
        } finally { observations.byRequest.remove(id); }
    }
    void problem(Reply reply, int status, String code) {
        assertThat(reply.response.statusCode()).isEqualTo(status);
        assertThat(reply.response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(reply.json().path("status").asInt()).isEqualTo(status);
        assertThat(reply.json().path("code").asText()).isEqualTo(code);
        assertThat(reply.json().path("type").asText()).isEqualTo("about:blank");
        assertThatCode(() -> UUID.fromString(reply.json().path("traceId").asText())).doesNotThrowAnyException();
        assertThat(reply.response.body()).doesNotContain("org.ip", "select ", "ATTR:", "ENTITY:", "REST:", "stackTrace");
        assertThat(reply.response.headers().firstValue("WWW-Authenticate").orElse(null))
            .isEqualTo(status == 401 ? "Basic realm=\"rest-read\"" : null);
        assertThat(reply.response.headers().firstValue("Allow").orElse(null)).isEqualTo(status == 405 ? "GET" : null);
        assertThat(reply.response.headers().firstValue("Location")).isEmpty();
        assertThat(reply.response.headers().firstValue("Cache-Control")).contains("no-store");
        if (status == 404 && reply.response.uri().getPath().matches("/api/v[^/]+/[^/]+/[^/]+")) {
            assertThat(reply.observation.count(Kind.CONTENT)).isBetween(0L, 1L);
            assertThat(reply.observation.count(Kind.COUNT)).isZero();
        } else assertThat(reply.observation.count(Kind.CONTENT) + reply.observation.count(Kind.COUNT)).as("no business SQL on rejection").isZero();
    }
    void success(Reply reply) {
        assertThat(reply.response.statusCode()).as(reply.response.body()).isEqualTo(200);
        assertThat(reply.response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(reply.observation.serializations).isEqualTo(1);
        assertThat(reply.observation.count(Kind.OTHER)).as(reply.observation.sql.toString()).isZero();
    }
    /** Frozen maxima measured before the final run; applies to maximum projections
     * with the declared fixture filter, and zero or exactly two assigned roles. */
    void assertMaximumBudget(Reply reply, boolean spec, boolean detail, boolean viaTwoRoles) {
        var observation = reply.observation();
        int permissions = spec ? 17 : detail ? 9 : 12;
        assertThat(observation.count(Kind.AUTHENTICATION)).isEqualTo(2);
        assertThat(observation.count(Kind.C5)).isBetween(1L, (long) permissions * (viaTwoRoles ? 3 : 1));
        assertThat(observation.count(Kind.ROLE)).isBetween(1L, (long) (viaTwoRoles ? permissions + 3 : 3));
        assertThat(observation.count(Kind.RLS)).isEqualTo(viaTwoRoles ? 4 : 2);
        assertThat(observation.count(Kind.CONTENT)).isEqualTo(1);
        assertThat(observation.count(Kind.COUNT)).isEqualTo(detail ? 0 : 1);
        assertThat(observation.count(Kind.OTHER)).isZero();
    }
    void assertGraphs(Reply reply, boolean spec, boolean detail) {
        assertThat(reply.observation().graphs).isNotEmpty();
        for (var graph : reply.observation().graphs) {
            assertThat(graph.get("unit")).isFalse(); assertThat(graph.get("group")).isFalse();
            if (spec) {
                assertThat(graph.get("nomenclature")).isTrue(); assertThat(graph.get("journal")).isEqualTo(detail);
                assertThat(graph.get("materials")).isFalse(); assertThat(graph.get("operations")).isFalse();
            }
        }
    }
    void grant(AccessGrant.SubjectType type, String key, String... dimensions) {
        RlsTestFixture.runAsSuperuser(grants, () -> {
            for (String dimension : dimensions) {
                var grant = new AccessGrant(); grant.setSubjectType(type); grant.setSubjectKey(key); grant.setDimension(dimension); grant.setCanRead(true); grants.save(grant);
            }
            grants.flush();
        });
    }
    void rowGrant(AccessGrant.SubjectType type, String key, Long journal) {
        RlsTestFixture.runAsSuperuser(grants, () -> {
            var grant = new AccessGrant(); grant.setSubjectType(type); grant.setSubjectKey(key); grant.setDimension("JOURNAL"); grant.setDimensionValueId(journal); grant.setCanRead(true); grants.saveAndFlush(grant);
        });
    }
    void revoke(String user, String dimension) {
        RlsTestFixture.runAsSuperuser(grants, () -> {
            grants.deleteAll(grants.findBySubjectTypeAndSubjectKeyAndDimension(AccessGrant.SubjectType.USER, user, dimension)); grants.flush();
        });
    }
    String[] allReadKeys() {
        Set<String> keys = new LinkedHashSet<>(List.of("REST:specifications:v1:LIST", "REST:specifications:v1:DETAIL",
            "REST:nomenclature:v1:LIST", "REST:nomenclature:v1:DETAIL"));
        for (Class<?> type : List.of(PrdSpec.class, Nomenclature.class, Journal.class)) keys.add(C5PermissionKeys.entityRead(type));
        return keys.toArray(String[]::new);
    }
    void seedRows(int amount) {
        RlsTestFixture.runAsSuperuser(grants, () -> {
            var journal = journals.findById(fixture.journalA()).orElseThrow();
            var original = nomenclatures.findById(fixture.nomId()).orElseThrow();
            var unit = units.findById(fixture.unitId()).orElseThrow();
            List<PrdSpec> specs = new ArrayList<>(); List<Nomenclature> noms = new ArrayList<>();
            for (int index = 1; index < amount; index++) {
                var spec = new PrdSpec(); spec.setCodeSpec("SAME"); spec.setJournal(journal); spec.setNomenclature(original); specs.add(spec);
                var nom = new Nomenclature(); nom.setCode(fixture.nomCode() + String.format(java.util.Locale.ROOT, "%04d", index));
                nom.setGroupNom(original.getGroupNom());
                nom.setName("Row " + index); nom.setTypeNom(fixture.typeNom()); nom.setUnitOfMeasurement(unit); noms.add(nom);
            }
            specifications.saveAllAndFlush(specs); nomenclatures.saveAllAndFlush(noms);
        });
    }
    record Fixture(Long journalA, Long journalB, Long nomId, String nomCode, String nomName, String typeNom, Long specA, Long specB, Long unitId) { }
    record Reply(HttpResponse<String> response, Observation observation) { JsonNode json() { return JSON.readTree(response.body()); } }
    enum Kind { AUTHENTICATION, C5, ROLE, RLS, CONTENT, COUNT, OTHER }
    static final class Observation {
        final List<String> sql = new ArrayList<>(); final List<Kind> kinds = new ArrayList<>();
        final List<Map<String, Boolean>> graphs = new ArrayList<>();
        final List<String> sessionMutations = new ArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);
        int serializations;
        boolean session, securityClean, markerClean, requestClean;
        long count(Kind kind) { return kinds.stream().filter(candidate -> candidate == kind).count(); }
        void print(String label) {
            System.out.printf("REST-HTTP-46 %s total=%d auth=%d C5=%d roles=%d RLS=%d content=%d count=%d other=%d serializationSQL=0%n",
                label, sql.size(), count(Kind.AUTHENTICATION), count(Kind.C5), count(Kind.ROLE), count(Kind.RLS), count(Kind.CONTENT), count(Kind.COUNT), count(Kind.OTHER));
        }
    }
    static class ObservationStore implements org.springframework.beans.factory.InitializingBean, org.springframework.beans.factory.DisposableBean {
        final Map<String, Observation> byRequest = new ConcurrentHashMap<>();
        final ThreadLocal<Observation> current = new ThreadLocal<>();
        final SqlStatementAudit audit = sql -> {
            var observation = current.get(); if (observation == null) return;
            String text = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            Kind kind;
            if (text.contains(" from access_grant ")) kind = text.contains("dimension_value_id is null") ? Kind.C5 : Kind.RLS;
            else if (text.contains(" from app_user ") || text.contains(" from user_role ") || text.contains(" from role ")) kind = SecurityContextHolder.getContext().getAuthentication() == null ? Kind.AUTHENTICATION : Kind.ROLE;
            else if (text.contains(" from prd_spec ") || text.contains(" from nomenclature ")) kind = text.contains("count(") ? Kind.COUNT : Kind.CONTENT;
            else kind = Kind.OTHER;
            observation.sql.add(text); observation.kinds.add(kind);
        };
        public void afterPropertiesSet() { SqlStatementAuditBridge.install(audit); }
        public void destroy() { SqlStatementAuditBridge.uninstall(audit); }
    }
    @Aspect
    static class GraphObserver {
        final ObservationStore store; final EntityManagerFactory emf;
        GraphObserver(ObservationStore store, EntityManagerFactory emf) { this.store = store; this.emf = emf; }
        @AfterReturning(pointcut = "execution(* org.ipro.data.EntityReadAccess.list(..)) || execution(* org.ipro.data.EntityReadAccess.detail(..))", returning = "result")
        public void observe(Object result) {
            var observation = store.current.get(); if (observation == null) return;
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(RlsContext.isBypassed()).isFalse();
            List<?> entities = result instanceof org.springframework.data.domain.Page<?> page ? page.getContent()
                : result instanceof Optional<?> optional ? optional.stream().toList() : List.of();
            var persistence = emf.getPersistenceUnitUtil();
            for (Object entity : entities) {
                var graph = new LinkedHashMap<String, Boolean>();
                if (entity instanceof PrdSpec spec) {
                    graph.put("nomenclature", persistence.isLoaded(spec, "nomenclature"));
                    graph.put("journal", persistence.isLoaded(spec, "journal"));
                    graph.put("materials", persistence.isLoaded(spec, "materials"));
                    graph.put("operations", persistence.isLoaded(spec, "operations"));
                    if (graph.get("nomenclature")) {
                        graph.put("unit", persistence.isLoaded(spec.getNomenclature(), "unitOfMeasurement"));
                        graph.put("group", persistence.isLoaded(spec.getNomenclature(), "groupNom"));
                    }
                } else if (entity instanceof Nomenclature nom) {
                    graph.put("unit", persistence.isLoaded(nom, "unitOfMeasurement")); graph.put("group", persistence.isLoaded(nom, "groupNom"));
                }
                if (!graph.isEmpty()) observation.graphs.add(graph);
            }
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    @com.vaadin.flow.spring.annotation.EnableVaadin("org.ip.views.login")
    static class ObservationConfiguration {
        @Bean FilterRegistrationBean<Filter> pilotSessionFixtureFilter() {
            Filter filter = (request, response, chain) -> {
                var http = (jakarta.servlet.http.HttpServletRequest) request;
                boolean create = http.getRequestURI().equals("/pilot/session");
                if (!create && !http.getRequestURI().equals("/pilot/session-state")) { chain.doFilter(request, response); return; }
                var session = http.getSession(create);
                assertThat(session).isNotNull();
                String key = org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
                if (create) {
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "session-only-user", "n/a", List.of()));
                    session.setAttribute(key, context);
                }
                var stored = (org.springframework.security.core.context.SecurityContext) session.getAttribute(key);
                response.setContentType("application/json");
                response.getWriter().write("{\"id\":\"" + session.getId() + "\",\"username\":\"" + stored.getAuthentication().getName() + "\"}");
            };
            var registration = new FilterRegistrationBean<>(filter); registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1); return registration;
        }
        @Bean ObservationStore observationStore() { return new ObservationStore(); }
        @Bean GraphObserver graphObserver(ObservationStore store, EntityManagerFactory emf) { return new GraphObserver(store, emf); }
        @Bean FilterRegistrationBean<Filter> pilotObservationFilter(ObservationStore store) {
            Filter filter = (request, response, chain) -> {
                var http = (jakarta.servlet.http.HttpServletRequest) request;
                String requestId = http.getHeader("X-Rest-Pilot-Request");
                var observation = requestId == null ? null : store.byRequest.get(requestId);
                if (observation == null) { chain.doFilter(request, response); return; }
                store.current.set(observation);
                var observedRequest = new jakarta.servlet.http.HttpServletRequestWrapper(http) {
                    @Override public String changeSessionId() {
                        observation.sessionMutations.add(StackWalker.getInstance().walk(frames -> frames.limit(15)
                            .map(Object::toString).collect(java.util.stream.Collectors.joining("\n"))));
                        return super.changeSessionId();
                    }
                };
                try { chain.doFilter(observedRequest, response); }
                finally {
                    observation.session = http.getSession(false) != null;
                    observation.securityClean = SecurityContextHolder.getContext().getAuthentication() == null;
                    observation.markerClean = !RestRequestContext.isActive();
                    observation.requestClean = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() == null;
                    store.current.remove(); observation.completed.countDown();
                }
            };
            var registration = new FilterRegistrationBean<>(filter); registration.setOrder(Ordered.HIGHEST_PRECEDENCE); return registration;
        }
        @Bean RestResourceDefinition<Nomenclature> httpPilotLimitedResource() {
            return RestResources.publish("limited-nomenclature", 1, Nomenclature.class)
                .field("id", RestFieldType.LONG, RestResources.path("id"), RestNullability.NOT_NULL)
                .field("code", RestFieldType.STRING, RestResources.path("code"), RestNullability.NOT_NULL)
                .listFields("id", "code").listDefaultFields("id", "code").detailFields("id", "code").detailDefaultFields("id", "code")
                .sortBy("id").defaultSort("id", RestSortDirection.ASC).pageSize(1, 2).build();
        }
    }
}
