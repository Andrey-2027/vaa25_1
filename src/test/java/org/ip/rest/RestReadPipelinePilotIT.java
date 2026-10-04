package org.ip.rest;

import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.UnitOfMeasurement;
import org.ip.repository.JournalRepository;
import org.ip.repository.NomenclatureRepository;
import org.ip.repository.PrdSpecRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.RestReadOperation;
import org.ipro.data.EntityReadAccess;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.data.ScenarioFetchGraphResolver;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.rest.service.RestDetailQuery;
import org.ipro.rest.service.RestListQuery;
import org.ipro.rest.service.RestPageResult;
import org.ipro.rest.service.RestReadOutcome;
import org.ipro.rest.service.RestReadService;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsTestFixture;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.telemetry.api.SqlStatementAudit;
import org.ipro.telemetry.core.SqlStatementAuditBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitUtil;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Full-context pilot for the application REST resources. The C5 evaluator, grant store,
 * Hibernate filters, authentication identity, C5 grants and canonical read path are real
 * application mechanisms; only the demo-data initializer is replaced by the test.
 */
@SpringBootTest(classes = Application.class)
@TestPropertySource(properties = {
    "platform.rest.read.enabled=true",
    "platform.rest.read.max-window=10000",
    "spring.jpa.open-in-view=false",
    "spring.jpa.properties.hibernate.cache.use_second_level_cache=false"
})
class RestReadPipelinePilotIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private RestResourceCatalog catalog;

    @Autowired
    private RestReadService readService;

    @Autowired
    private EntityReadAccess entityReadAccess;

    @Autowired
    private EntityDataAccessResolver dataAccessResolver;

    @Autowired
    private ScenarioFetchGraphResolver graphResolver;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JpaTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private RlsDimensionRegistry dimensionRegistry;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private UnitOfMeasurementRepository unitOfMeasurementRepository;

    @Autowired
    private NomenclatureRepository nomenclatureRepository;

    @Autowired
    private PrdSpecRepository prdSpecRepository;

    private String subject;

    @BeforeEach
    void bindAuthenticatedSubject() {
        subject = "rest-pilot-" + UUID.randomUUID().toString().substring(0, 8);
        loginAs(subject);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Gate 3A: Startup registers REST operations in RlsDimensionRegistry")
    void startupRegistrationRegistersRestOperations() {
        assertThat(dimensionRegistry.dimensions()).contains(
            "REST:specifications:v1:LIST",
            "REST:specifications:v1:DETAIL",
            "REST:nomenclature:v1:LIST",
            "REST:nomenclature:v1:DETAIL",
            C5PermissionKeys.entityRead(PrdSpec.class),
            C5PermissionKeys.entityRead(Nomenclature.class),
            C5PermissionKeys.attributeRead(PrdSpec.class, "codeSpec")
        );
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("Gate 3A: Rejects anonymous or unauthenticated caller")
    void rejectsUnauthenticatedCaller() {
        SecurityContextHolder.clearContext();

        RestListQuery query = new RestListQuery("specifications", 1,
            List.of("id"), Map.of(), null, null, 0, 10);
        assertThatThrownBy(() -> readService.list(query))
            .isInstanceOf(org.ipro.rest.service.RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("Gate 3A: Real C5 store denies a caller without an API grant")
    void deniesCallerWithoutApiGrant() {
        RestListQuery query = new RestListQuery("specifications", 1,
            List.of("id"), Map.of(), null, null, 0, 10);
        assertThatThrownBy(() -> readService.list(query))
            .isInstanceOf(org.ipro.rest.service.RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN);
    }

    @Test
    @DisplayName("Gate 3B: Real C5 grants allow scalar Nomenclature list and detail")
    void nomenclatureReadUsesPersistedC5GrantsAndReturnsScalars() {
        Fixture fixture = persistFixture();
        grantNomenclatureRead(subject);

        RestPageResult page = readService.list(new RestListQuery("nomenclature", 1,
            List.of("id", "code", "name"),
            Map.of("typeNom", "RT" + fixture.nomenclatureCode().substring(1)),
            null, null, 0, 10));

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("id", fixture.nomenclatureId())
                .containsEntry("code", fixture.nomenclatureCode())
                .containsEntry("name", fixture.nomenclatureName())
                .doesNotContainKey("typeNom");
            assertThatThrownBy(() -> row.put("injected", true))
                .isInstanceOf(UnsupportedOperationException.class);
        });
        assertReturnedOutsideTransaction(page.content());

        grant(subject, "REST:nomenclature:v1:DETAIL");
        var detail = readService.detail(new RestDetailQuery("nomenclature", 1,
            List.of("id", "code", "name"), fixture.nomenclatureId()));
        assertThat(detail.data()).containsEntry("id", fixture.nomenclatureId())
            .containsEntry("code", fixture.nomenclatureCode())
            .containsEntry("name", fixture.nomenclatureName());
        assertReturnedOutsideTransaction(List.of(detail.data()));
    }

    @Test
    @DisplayName("Gate 3B: Two subjects see only their Journal rows and matching count")
    void prdSpecListAndDetailRespectJournalGrantsForEachSubject() {
        Fixture fixture = persistFixture();
        grantPrdSpecRead("rest-alice", fixture.journalAId());
        grantPrdSpecRead("rest-bob", fixture.journalBId());

        loginAs("rest-alice");
        RestPageResult alice = readSpecList();
        assertThat(alice.totalElements()).isEqualTo(1);
        assertThat(alice.content()).singleElement().satisfies(row ->
            assertThat(row).containsEntry("codeSpec", fixture.specACode()));

        var visibleDetail = readService.detail(new RestDetailQuery("specifications", 1,
            List.of("id", "codeSpec", "journalId", "nomenclatureCode"), fixture.specAId()));
        assertThat(visibleDetail.data()).containsEntry("codeSpec", fixture.specACode())
            .containsEntry("journalId", fixture.journalAId())
            .containsEntry("nomenclatureCode", fixture.nomenclatureCode());
        assertReturnedOutsideTransaction(List.of(visibleDetail.data()));

        LoadedAssociations listGraph = inspectLoadedAssociations(fixture.specAId(), RestReadOperation.LIST);
        assertThat(listGraph.nomenclatureLoaded()).isTrue();
        assertThat(listGraph.nomenclatureCodeLoaded()).isTrue();
        assertThat(listGraph.journalLoaded()).isFalse();
        LoadedAssociations detailGraph = inspectLoadedAssociations(fixture.specAId(), RestReadOperation.DETAIL);
        assertThat(detailGraph.nomenclatureLoaded()).isTrue();
        assertThat(detailGraph.nomenclatureCodeLoaded()).isTrue();
        assertThat(detailGraph.journalLoaded()).isTrue();
        assertThat(detailGraph.journalIdLoaded()).isTrue();

        // An explicit filter cannot widen the caller's row set.
        RestPageResult filteredToHiddenJournal = readService.list(new RestListQuery("specifications", 1,
            List.of("id", "codeSpec"), Map.of("journalId", fixture.journalBId()),
            null, null, 0, 10));
        assertThat(filteredToHiddenJournal.content()).isEmpty();
        assertThat(filteredToHiddenJournal.totalElements()).isZero();

        RestPageResult filteredToVisibleJournal = readService.list(new RestListQuery("specifications", 1,
            List.of("id", "codeSpec"), Map.of("journalId", fixture.journalAId()),
            null, null, 0, 10));
        assertThat(filteredToVisibleJournal.totalElements()).isEqualTo(1);
        assertThat(filteredToVisibleJournal.content()).singleElement().satisfies(row ->
            assertThat(row).containsEntry("codeSpec", fixture.specACode()));

        loginAs("rest-bob");
        RestPageResult bob = readSpecList();
        assertThat(bob.totalElements()).isEqualTo(1);
        assertThat(bob.content()).singleElement().satisfies(row ->
            assertThat(row).containsEntry("codeSpec", fixture.specBCode()));

        var hiddenDetail = new RestDetailQuery("specifications", 1,
            List.of("id", "codeSpec"), fixture.specAId());
        assertThatThrownBy(() -> readService.detail(hiddenDetail))
            .isInstanceOf(org.ipro.rest.service.RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.DETAIL_NOT_FOUND);
        assertThatThrownBy(() -> readService.detail(new RestDetailQuery("specifications", 1,
            List.of("id", "codeSpec"), Long.MAX_VALUE)))
            .isInstanceOf(org.ipro.rest.service.RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.DETAIL_NOT_FOUND);
    }

    @Test
    @DisplayName("Gate 3B: 1-row and 50-row REST pages use a fixed SQL budget and scalar results")
    void listSqlCountDoesNotGrowFromOneToFiftyRows() {
        Fixture fixture = persistFixture();
        addSpecificationsForJournal(fixture, 49);
        grantPrdSpecRead("rest-sql-budget", fixture.journalAId());
        loginAs("rest-sql-budget");

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        RestListQuery budgetQuery = new RestListQuery("specifications", 1,
            List.of("id", "codeSpec", "nomenclatureCode"), Map.of("journalId", fixture.journalAId()),
            null, null, 0, 1);
        statistics.clear();
        SqlRead<RestPageResult> firstRead = recordSql("PrdSpec REST first LIST size=1",
            () -> readService.list(budgetQuery));
        long firstReadSql = statistics.getPrepareStatementCount();
        assertThat(firstRead.result().totalElements()).isEqualTo(50);
        assertThat(firstRead.result().content()).hasSize(1);
        assertThat(firstReadSql).as("first PrdSpec LIST page-size 1 SQL budget").isEqualTo(20);
        assertThat(firstRead.statements()).hasSize((int) firstReadSql);

        // Fresh persistence contexts and L2-off do not reset the separate session-scoped
        // RlsReadableIdsCache. C5 itself queries exact grants on every permission check.
        statistics.clear();
        SqlRead<RestPageResult> oneRowRead = recordSql("PrdSpec REST repeated LIST size=1",
            () -> readService.list(budgetQuery));
        RestPageResult oneRow = oneRowRead.result();
        long oneRowSql = statistics.getPrepareStatementCount();
        assertThat(oneRow.totalElements()).isEqualTo(50);
        assertThat(oneRow.content()).hasSize(1);
        assertReturnedOutsideTransaction(oneRow.content());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(oneRowSql);

        statistics.clear();
        SqlRead<RestPageResult> fiftyRowRead = recordSql("PrdSpec REST repeated LIST size=50",
            () -> readService.list(new RestListQuery("specifications", 1,
            List.of("id", "codeSpec", "nomenclatureCode"), Map.of("journalId", fixture.journalAId()),
            null, null, 0, 50)));
        RestPageResult fiftyRows = fiftyRowRead.result();
        long fiftyRowSql = statistics.getPrepareStatementCount();
        assertThat(fiftyRows.totalElements()).isEqualTo(50);
        assertThat(fiftyRows.content()).hasSize(50);
        assertReturnedOutsideTransaction(fiftyRows.content());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(fiftyRowSql);
        assertThat(oneRowSql).isEqualTo(16)
            .as("repeated PrdSpec LIST page-size 1 SQL budget");
        assertThat(fiftyRowSql).isEqualTo(16)
            .as("repeated PrdSpec LIST page-size 50 SQL budget");
        assertThat(fiftyRowSql).isEqualTo(oneRowSql)
            .as("SQL statement count is independent of REST page row count");
        assertThat(oneRowRead.statements()).hasSize((int) oneRowSql);
        assertThat(fiftyRowRead.statements()).hasSize((int) fiftyRowSql);
        assertThat(firstRead.count(SqlKind.C5_GRANT)).isEqualTo(oneRowRead.count(SqlKind.C5_GRANT));
        assertThat(oneRowRead.count(SqlKind.C5_GRANT)).isEqualTo(fiftyRowRead.count(SqlKind.C5_GRANT));
        for (SqlRead<?> measured : List.of(firstRead, oneRowRead, fiftyRowRead)) {
            assertThat(measured.count(SqlKind.CONTENT)).isEqualTo(1);
            assertThat(measured.count(SqlKind.COUNT)).isEqualTo(1);
            assertThat(measured.count(SqlKind.OTHER)).isZero();
        }
        assertThat(firstRead.count(SqlKind.RLS_GRANT) - oneRowRead.count(SqlKind.RLS_GRANT)).isEqualTo(2);
        assertThat(firstRead.count(SqlKind.ROLE_RESOLUTION) - oneRowRead.count(SqlKind.ROLE_RESOLUTION))
            .isEqualTo(2);
    }

    @Test
    @DisplayName("Stage 3 completion baseline: UI LIST and fixed REST profiles on the same rows")
    void uiListAndRestFixedProfilesHaveSeparateBaselines() {
        Fixture fixture = persistFixture();
        grantPrdSpecRead(subject, fixture.journalAId());
        grantNomenclatureRead(subject);
        RestListQuery specQuery = new RestListQuery("specifications", 1,
            List.of("id", "codeSpec", "nomenclatureCode"), Map.of("journalId", fixture.journalAId()),
            null, null, 0, 1);
        // Prime only session RLS ids; each measured call opens its own persistence context.
        readService.list(specQuery);
        printGraphBaseline("specifications", PrdSpec.class);
        printGraphBaseline("nomenclature", Nomenclature.class);

        Specification<PrdSpec> sameJournal = (root, query, cb) ->
            cb.equal(root.get("journal").get("id"), fixture.journalAId());
        var uiSpec = recordSql("PrdSpec UI LIST size=1 RLS-ids-already-resolved", () ->
            dataAccessResolver.resolve(PrdSpec.class).list(PrdSpec.class, sameJournal,
                PageRequest.of(0, 1, Sort.by("id"))));
        var restSpec = recordSql("PrdSpec REST LIST size=1 RLS-ids-already-resolved", () ->
            readService.list(specQuery));
        assertThat(uiSpec.result().getTotalElements()).isEqualTo(restSpec.result().totalElements());
        assertThat(uiSpec.result().getContent()).extracting(PrdSpec::getId)
            .containsExactly(fixture.specAId());
        assertThat(restSpec.result().content()).singleElement().satisfies(row ->
            assertThat(row).containsEntry("id", fixture.specAId()));
        var loadedSpec = uiSpec.result().getContent().getFirst();
        PersistenceUnitUtil persistence = entityManagerFactory.getPersistenceUnitUtil();
        System.out.printf("REST-STAGE3 UI PrdSpec loaded journal=%s nomenclature=%s materials=%s operations=%s%n",
            persistence.isLoaded(loadedSpec, "journal"), persistence.isLoaded(loadedSpec, "nomenclature"),
            persistence.isLoaded(loadedSpec, "materials"), persistence.isLoaded(loadedSpec, "operations"));

        Specification<Nomenclature> sameType = (root, query, cb) ->
            cb.equal(root.get("typeNom"), fixture.nomenclatureTypeNom());
        var uiNom = recordSql("Nomenclature UI LIST size=1 RLS-ids-already-resolved", () ->
            dataAccessResolver.resolve(Nomenclature.class).list(Nomenclature.class, sameType,
                PageRequest.of(0, 1, Sort.by("code").and(Sort.by("id")))));
        var restNom = recordSql("Nomenclature REST LIST size=1 RLS-ids-already-resolved", () ->
            readService.list(new RestListQuery("nomenclature", 1,
                List.of("id", "code", "name", "typeNom"), Map.of("typeNom", fixture.nomenclatureTypeNom()),
                null, null, 0, 1)));
        assertThat(uiNom.result().getTotalElements()).isEqualTo(restNom.result().totalElements());
        assertThat(uiNom.result().getContent()).extracting(Nomenclature::getId)
            .containsExactly(fixture.nomenclatureId());
        assertThat(restNom.result().content()).singleElement().satisfies(row ->
            assertThat(row).containsEntry("id", fixture.nomenclatureId()));
        var loadedNom = uiNom.result().getContent().getFirst();
        System.out.printf("REST-STAGE3 UI Nomenclature loaded unitOfMeasurement=%s groupNom=%s%n",
            persistence.isLoaded(loadedNom, "unitOfMeasurement"), persistence.isLoaded(loadedNom, "groupNom"));
    }

    @Test
    @DisplayName("Z1: full pages at the window boundary and rejection before business SQL")
    void pageWindowBoundaryUsesActualRowsForBothResources() {
        Fixture fixture = persistFixture();
        seedWindowRows(fixture, 10_000);
        grantPrdSpecRead(subject, fixture.journalAId());
        grantNomenclatureRead(subject);
        for (String resourceKey : List.of("specifications", "nomenclature")) {
            var first = recordSql("Z1 " + resourceKey + " page=0 size=50", () ->
                readService.list(windowQuery(resourceKey, fixture, 0, 50)));
            assertWindowPage(first, 0, 50);
            var last = recordSql("Z1 " + resourceKey + " page=199 size=50", () ->
                readService.list(windowQuery(resourceKey, fixture, 199, 50)));
            assertWindowPage(last, 199, 50);
            var defaultSize = recordSql("Z1 " + resourceKey + " page=199 size=default", () ->
                readService.list(windowQuery(resourceKey, fixture, 199, 0)));
            assertWindowPage(defaultSize, 199, 50);
            assertThat(defaultSize.result().content()).isEqualTo(last.result().content());
            var maxSize = recordSql("Z1 " + resourceKey + " page=49 size=200", () ->
                readService.list(windowQuery(resourceKey, fixture, 49, 200)));
            assertWindowPage(maxSize, 49, 200);
            assertThat(maxSize.result().content().subList(150, 200)).isEqualTo(last.result().content());

            for (int[] pageAndSize : List.of(new int[]{200, 50}, new int[]{50, 200}, new int[]{200, 0})) {
                var denied = recordSql("Z1 " + resourceKey + " rejected page=" + pageAndSize[0]
                    + " size=" + pageAndSize[1], () -> catchThrowable(() ->
                    readService.list(windowQuery(resourceKey, fixture, pageAndSize[0], pageAndSize[1]))));
                assertThat(denied.result()).isInstanceOf(org.ipro.rest.service.RestReadException.class)
                    .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.INVALID_REQUEST);
                assertThat(denied.count(SqlKind.C5_GRANT)).isEqualTo(2);
                assertThat(denied.statements()).hasSize(2);
                assertThat(denied.count(SqlKind.CONTENT)).isZero();
                assertThat(denied.count(SqlKind.COUNT)).isZero();
            }
        }
    }

    private RestListQuery windowQuery(String resourceKey, Fixture fixture, int page, int size) {
        boolean specifications = resourceKey.equals("specifications");
        return new RestListQuery(resourceKey, 1,
            specifications ? List.of("id", "codeSpec", "nomenclatureCode") : List.of("id", "code", "name", "typeNom"),
            specifications ? Map.of("journalId", fixture.journalAId()) : Map.of("typeNom", fixture.nomenclatureTypeNom()),
            null, null, page, size);
    }

    private void assertWindowPage(SqlRead<RestPageResult> read, int page, int size) {
        assertThat(read.result().page()).isEqualTo(page);
        assertThat(read.result().size()).isEqualTo(size);
        assertThat(read.result().content()).hasSize(size);
        assertThat(read.result().totalElements()).isEqualTo(10_001);
        assertThat(read.count(SqlKind.CONTENT)).isEqualTo(1);
        assertThat(read.count(SqlKind.COUNT)).isEqualTo(1);
        assertThat(read.count(SqlKind.OTHER)).isZero();
        assertReturnedOutsideTransaction(read.result().content());
    }

    private void seedWindowRows(Fixture fixture, int amount) {
        // H2-only bulk fixture DML keeps setup outside the measured Hibernate read path.
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            assertThat(jdbcTemplate.update("""
                insert into prd_spec (code_spec, journal_id, nomenclature_id, version)
                select concat(?, cast(x as varchar)), ?, ?, 0 from system_range(1, ?)
                """, "WX" + token, fixture.journalAId(), fixture.nomenclatureId(), amount)).isEqualTo(amount);
            assertThat(jdbcTemplate.update("""
                insert into nomenclature (code, name, type_nom, unit_id, version)
                select concat(?, cast(x as varchar)), 'Window fixture', ?, ?, 0 from system_range(1, ?)
                """, "WY" + token, fixture.nomenclatureTypeNom(), fixture.unitOfMeasurementId(), amount))
                .isEqualTo(amount);
        });
    }

    private void printGraphBaseline(String resourceKey, Class<?> rootType) {
        var resource = catalog.find(resourceKey, 1).orElseThrow();
        System.out.printf("REST-STAGE3 GRAPH %s UI-LIST=%s REST-LIST=%s REST-DETAIL=%s%n", resourceKey,
            graphResolver.resolvePaths(rootType, FetchScenario.LIST, List.of()),
            resource.fixedFetchPaths(RestReadOperation.LIST), resource.fixedFetchPaths(RestReadOperation.DETAIL));
    }

    private <T> SqlRead<T> recordSql(String label, Supplier<T> read) {
        List<String> statements = new ArrayList<>();
        Thread measuredThread = Thread.currentThread();
        SqlStatementAudit audit = sql -> {
            if (Thread.currentThread() == measuredThread) {
                statements.add(sql.replaceAll("\\s+", " ").trim());
            }
        };
        SqlStatementAuditBridge.install(audit);
        SqlRead<T> result;
        try {
            result = new SqlRead<>(read.get(), List.copyOf(statements));
        } finally {
            SqlStatementAuditBridge.uninstall(audit);
        }
        System.out.printf("REST-STAGE3 SQL %s total=%d C5=%d RLS=%d roles=%d content=%d count=%d other=%d%n",
            label, result.statements().size(), result.count(SqlKind.C5_GRANT), result.count(SqlKind.RLS_GRANT),
            result.count(SqlKind.ROLE_RESOLUTION), result.count(SqlKind.CONTENT), result.count(SqlKind.COUNT),
            result.count(SqlKind.OTHER));
        result.statements().forEach(sql -> System.out.printf("REST-STAGE3 %s %s%n", sqlKind(sql), sql));
        return result;
    }

    private static SqlKind sqlKind(String sql) {
        String normalized = sql.toLowerCase(Locale.ROOT);
        // Categories are specific to this H2 pilot's statements; unknown SQL remains visible.
        if (normalized.contains(" from access_grant ")) {
            return normalized.contains("dimension_value_id is null") ? SqlKind.C5_GRANT : SqlKind.RLS_GRANT;
        }
        if (normalized.contains(" from app_user ") || normalized.contains(" from user_role ")
                || normalized.contains(" from role ")) {
            return SqlKind.ROLE_RESOLUTION;
        }
        if (normalized.contains(" from prd_spec ") || normalized.contains(" from nomenclature ")) {
            return normalized.contains("count(") ? SqlKind.COUNT : SqlKind.CONTENT;
        }
        return SqlKind.OTHER;
    }

    private enum SqlKind { C5_GRANT, RLS_GRANT, ROLE_RESOLUTION, CONTENT, COUNT, OTHER }

    private record SqlRead<T>(T result, List<String> statements) {
        long count(SqlKind kind) {
            return statements.stream().filter(sql -> sqlKind(sql) == kind).count();
        }
    }

    private LoadedAssociations inspectLoadedAssociations(Long specId, RestReadOperation operation) {
        var resource = catalog.find("specifications", 1).orElseThrow();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            Specification<PrdSpec> allRows = (root, query, criteriaBuilder) -> criteriaBuilder.conjunction();
            PrdSpec entity;
            if (operation == RestReadOperation.LIST) {
                var page = entityReadAccess.list(PrdSpec.class, allRows,
                    PageRequest.of(0, 1), resource.fixedFetchPaths(operation));
                entity = page.getContent().getFirst();
            } else {
                entity = entityReadAccess.detail(PrdSpec.class, specId, resource.fixedFetchPaths(operation))
                    .orElseThrow();
            }
            PersistenceUnitUtil persistenceUnitUtil = entityManagerFactory.getPersistenceUnitUtil();
            boolean nomenclatureLoaded = persistenceUnitUtil.isLoaded(entity, "nomenclature");
            boolean journalLoaded = persistenceUnitUtil.isLoaded(entity, "journal");
            return new LoadedAssociations(nomenclatureLoaded,
                nomenclatureLoaded && persistenceUnitUtil.isLoaded(entity.getNomenclature(), "code"),
                journalLoaded,
                journalLoaded && persistenceUnitUtil.isLoaded(entity.getJournal(), "id"));
        });
    }

    private void assertReturnedOutsideTransaction(List<Map<String, Object>> rows) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(rows).allSatisfy(row -> assertThat(row.values()).allSatisfy(value -> {
            if (value != null) {
                assertThat(value).isInstanceOfAny(String.class, Number.class, Boolean.class,
                    Character.class, java.time.temporal.TemporalAccessor.class, UUID.class, Enum.class);
            }
        }));
    }

    private void addSpecificationsForJournal(Fixture fixture, int amount) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            Journal journal = journalRepository.findById(fixture.journalAId()).orElseThrow();
            Nomenclature nomenclature = nomenclatureRepository.findById(fixture.nomenclatureId()).orElseThrow();
            for (int index = 0; index < amount; index++) {
                PrdSpec spec = new PrdSpec();
                spec.setJournal(journal);
                spec.setNomenclature(nomenclature);
                spec.setCodeSpec("SX" + token + index);
                prdSpecRepository.save(spec);
            }
            return null;
        });
    }

    private RestPageResult readSpecList() {
        return readService.list(new RestListQuery("specifications", 1,
            List.of("id", "codeSpec"), Map.of(), null, null, 0, 10));
    }

    private Fixture persistFixture() {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            Journal journalA = new Journal();
            journalA.setCode("JA" + token);
            journalA.setName("Journal A " + token);
            journalA = journalRepository.save(journalA);

            Journal journalB = new Journal();
            journalB.setCode("JB" + token);
            journalB.setName("Journal B " + token);
            journalB = journalRepository.save(journalB);

            UnitOfMeasurement unit = new UnitOfMeasurement();
            unit.setCode("U" + token);
            unit.setShortCode("U" + token);
            unit.setName("Unit " + token);
            unit = unitOfMeasurementRepository.save(unit);

            Nomenclature nomenclature = new Nomenclature();
            nomenclature.setCode("N" + token);
            nomenclature.setName("Nomenclature " + token);
            nomenclature.setTypeNom("RT" + token);
            nomenclature.setUnitOfMeasurement(unit);
            nomenclature = nomenclatureRepository.save(nomenclature);

            PrdSpec specA = new PrdSpec();
            specA.setJournal(journalA);
            specA.setNomenclature(nomenclature);
            specA.setCodeSpec("SA" + token);
            specA = prdSpecRepository.save(specA);

            PrdSpec specB = new PrdSpec();
            specB.setJournal(journalB);
            specB.setNomenclature(nomenclature);
            specB.setCodeSpec("SB" + token);
            specB = prdSpecRepository.save(specB);
            return new Fixture(nomenclature.getId(), nomenclature.getCode(), nomenclature.getName(),
                nomenclature.getTypeNom(), unit.getId(),
                journalA.getId(), journalB.getId(), specA.getId(), specA.getCodeSpec(),
                specB.getId(), specB.getCodeSpec());
        });
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private void grantNomenclatureRead(String username) {
        grant(username, "REST:nomenclature:v1:LIST",
            C5PermissionKeys.entityRead(Nomenclature.class),
            C5PermissionKeys.attributeRead(Nomenclature.class, "id"),
            C5PermissionKeys.attributeRead(Nomenclature.class, "code"),
            C5PermissionKeys.attributeRead(Nomenclature.class, "name"),
            C5PermissionKeys.attributeRead(Nomenclature.class, "typeNom"));
    }

    private void grantPrdSpecRead(String username, Long journalId) {
        grant(username, "REST:specifications:v1:LIST", "REST:specifications:v1:DETAIL",
            C5PermissionKeys.entityRead(PrdSpec.class),
            C5PermissionKeys.attributeRead(PrdSpec.class, "id"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "codeSpec"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "nomenclature"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "journal"),
            C5PermissionKeys.entityRead(Nomenclature.class),
            C5PermissionKeys.attributeRead(Nomenclature.class, "code"),
            C5PermissionKeys.entityRead(Journal.class),
            C5PermissionKeys.attributeRead(Journal.class, "id"));
        AccessGrant rowGrant = new AccessGrant();
        rowGrant.setSubjectType(AccessGrant.SubjectType.USER);
        rowGrant.setSubjectKey(username);
        rowGrant.setDimension("JOURNAL");
        rowGrant.setDimensionValueId(journalId);
        rowGrant.setCanRead(true);
        accessGrantRepository.saveAndFlush(rowGrant);
    }

    private void grant(String username, String... dimensions) {
        for (String dimension : dimensions) {
            AccessGrant grant = new AccessGrant();
            grant.setSubjectType(AccessGrant.SubjectType.USER);
            grant.setSubjectKey(username);
            grant.setDimension(dimension);
            grant.setDimensionValueId(null);
            grant.setCanRead(true);
            accessGrantRepository.save(grant);
        }
        accessGrantRepository.flush();
    }

    private record Fixture(Long nomenclatureId, String nomenclatureCode, String nomenclatureName,
                           String nomenclatureTypeNom, Long unitOfMeasurementId,
                           Long journalAId, Long journalBId, Long specAId, String specACode,
                           Long specBId, String specBCode) {
    }

    private record LoadedAssociations(boolean nomenclatureLoaded, boolean nomenclatureCodeLoaded,
                                      boolean journalLoaded, boolean journalIdLoaded) {
    }
}
