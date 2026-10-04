package org.ip.rest;

import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.GroupNom;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.Role;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.User;
import org.ip.repository.JournalRepository;
import org.ip.repository.NomenclatureRepository;
import org.ip.repository.PrdSpecRepository;
import org.ip.repository.RoleRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ip.repository.UserRepository;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.RestReadOperation;
import org.ipro.data.CanonicalWriteExecutor;
import org.ipro.data.EntityReadAccess;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.data.ScenarioFetchGraphResolver;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.rest.service.RestDetailQuery;
import org.ipro.rest.service.RestDetailResult;
import org.ipro.rest.service.RestDetailResult;
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
import java.util.Set;
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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CanonicalWriteExecutor writeExecutor;

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
    @DisplayName("З4: LIST-бюджеты обоих ресурсов стабильны по проекции, фильтру и size 1/50/200")
    void z4ListBudgetsAcrossProjectionsAndSizes() {
        Fixture fixture = persistFixture();
        addSpecificationsForJournal(fixture, 199);
        addNomenclaturesForType(fixture, 199);
        grantPrdSpecRead(subject, fixture.journalAId());
        grantNomenclatureRead(subject);
        // Прогрев session RLS ids: измеряемые вызовы идут в свежих persistence context.
        readService.list(new RestListQuery("specifications", 1, List.of("id"), Map.of(), null, null, 0, 1));
        readService.list(new RestListQuery("nomenclature", 1, List.of("id"), Map.of(), null, null, 0, 1));

        List<Long> specDefaultC5 = new ArrayList<>();
        List<Long> specMaxC5 = new ArrayList<>();
        List<Long> nomDefaultC5 = new ArrayList<>();
        List<Long> nomMaxC5 = new ArrayList<>();
        List<Long> nomDefaultTotals = new ArrayList<>();
        for (int size : List.of(1, 50, 200)) {
            SqlRead<RestPageResult> specDefault = recordSql("Z4 PrdSpec LIST default size=" + size, () ->
                readService.list(new RestListQuery("specifications", 1, List.of(), Map.of(), null, null, 0, size)));
            assertDirectListBudget(specDefault, size, size, 200L, "PrdSpec default LIST");
            specDefaultC5.add(specDefault.count(SqlKind.C5_GRANT));

            SqlRead<RestPageResult> specMax = recordSql("Z4 PrdSpec LIST max+filter size=" + size, () ->
                readService.list(specMaxQuery(fixture, size)));
            assertDirectListBudget(specMax, size, size, 200L, "PrdSpec max LIST");
            assertThat(specMax.result().content()).allSatisfy(row ->
                assertThat(row).containsKeys("id", "codeSpec", "draft", "nomenclatureCode"));
            specMaxC5.add(specMax.count(SqlKind.C5_GRANT));

            SqlRead<RestPageResult> nomDefault = recordSql("Z4 Nomenclature LIST default size=" + size, () ->
                readService.list(new RestListQuery("nomenclature", 1, List.of(), Map.of(), null, null, 0, size)));
            // Unfiltered Nomenclature total зависит от остальных данных общей H2: ниже
            // проверяются консистентность total по size и нижняя граница, не точное число.
            assertDirectListBudget(nomDefault, size, size, null, "Nomenclature default LIST");
            nomDefaultTotals.add(nomDefault.result().totalElements());
            nomDefaultC5.add(nomDefault.count(SqlKind.C5_GRANT));

            SqlRead<RestPageResult> nomMax = recordSql("Z4 Nomenclature LIST max+filter size=" + size, () ->
                readService.list(nomMaxQuery(fixture, size)));
            assertDirectListBudget(nomMax, size, size, 200L, "Nomenclature max LIST");
            assertThat(nomMax.result().content()).allSatisfy(row ->
                assertThat(row).containsKeys("id", "code", "name", "typeNom"));
            nomMaxC5.add(nomMax.count(SqlKind.C5_GRANT));
        }
        assertConstantSecurityBudget("PrdSpec default LIST", specDefaultC5);
        assertConstantSecurityBudget("PrdSpec max LIST", specMaxC5);
        assertConstantSecurityBudget("Nomenclature default LIST", nomDefaultC5);
        assertConstantSecurityBudget("Nomenclature max LIST", nomMaxC5);
        assertThat(nomDefaultTotals).as("unfiltered Nomenclature total не зависит от size")
            .allMatch(total -> total.equals(nomDefaultTotals.get(0)));
        assertThat(nomDefaultTotals.get(0)).as("fixture даёт минимум 200 строк")
            .isGreaterThanOrEqualTo(200L);

        // Объявленный фильтр на скрытую/пустую цель не расширяет доступ и не меняет бизнес-бюджет.
        SqlRead<RestPageResult> hiddenSpec = recordSql("Z4 PrdSpec LIST hidden target filter", () ->
            readService.list(new RestListQuery("specifications", 1, List.of("id", "codeSpec"),
                Map.of("journalId", fixture.journalBId()), null, null, 0, 50)));
        assertThat(hiddenSpec.result().totalElements()).isZero();
        assertDirectListBudget(hiddenSpec, 0, 50, 0L, "PrdSpec hidden target LIST");

        SqlRead<RestPageResult> noMatchNom = recordSql("Z4 Nomenclature LIST no-match filter", () ->
            readService.list(new RestListQuery("nomenclature", 1, List.of("id", "code"),
                Map.of("typeNom", "ZZ" + UUID.randomUUID().toString().substring(0, 8)),
                null, null, 0, 50)));
        assertThat(noMatchNom.result().totalElements()).isZero();
        assertDirectListBudget(noMatchNom, 0, 50, 0L, "Nomenclature no-match LIST");
    }

    @Test
    @DisplayName("З4: DETAIL-бюджеты обоих ресурсов, hidden/missing и 0 SQL после scalar")
    void z4DetailBudgetsAndScalarConsumptionForBothResources() {
        Fixture fixture = persistFixture();
        grantPrdSpecRead(subject, fixture.journalAId());
        grantNomenclatureRead(subject);
        readService.detail(new RestDetailQuery("specifications", 1, List.of("id"), fixture.specAId()));
        readService.detail(new RestDetailQuery("nomenclature", 1, List.of("id"), fixture.nomenclatureId()));

        SqlRead<RestDetailResult> specDefault = recordSql("Z4 PrdSpec DETAIL default", () ->
            readService.detail(new RestDetailQuery("specifications", 1, List.of(), fixture.specAId())));
        assertDetailBudget(specDefault, List.of("id", "codeSpec", "draft", "comment"),
            "PrdSpec default DETAIL");

        SqlRead<RestDetailResult> specMax = recordSql("Z4 PrdSpec DETAIL max", () ->
            readService.detail(new RestDetailQuery("specifications", 1,
                List.of("id", "codeSpec", "draft", "comment", "journalId", "nomenclatureCode"),
                fixture.specAId())));
        assertDetailBudget(specMax,
            List.of("id", "codeSpec", "draft", "comment", "journalId", "nomenclatureCode"),
            "PrdSpec max DETAIL");
        assertThat(specMax.result().data()).containsEntry("journalId", fixture.journalAId())
            .containsEntry("nomenclatureCode", fixture.nomenclatureCode());

        SqlRead<Throwable> hidden = recordSql("Z4 PrdSpec DETAIL hidden", () -> catchThrowable(() ->
            readService.detail(new RestDetailQuery("specifications", 1, List.of("id"), fixture.specBId()))));
        assertThat(hidden.result()).isInstanceOf(org.ipro.rest.service.RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.DETAIL_NOT_FOUND);
        assertDetailLookupBudget(hidden, "PrdSpec hidden DETAIL");

        SqlRead<Throwable> missing = recordSql("Z4 PrdSpec DETAIL missing", () -> catchThrowable(() ->
            readService.detail(new RestDetailQuery("specifications", 1, List.of("id"), Long.MAX_VALUE))));
        assertThat(missing.result()).isInstanceOf(org.ipro.rest.service.RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.DETAIL_NOT_FOUND);
        assertDetailLookupBudget(missing, "PrdSpec missing DETAIL");

        SqlRead<RestDetailResult> nomDefault = recordSql("Z4 Nomenclature DETAIL default", () ->
            readService.detail(new RestDetailQuery("nomenclature", 1, List.of(), fixture.nomenclatureId())));
        assertDetailBudget(nomDefault, List.of("id", "code", "name"), "Nomenclature default DETAIL");

        SqlRead<RestDetailResult> nomMax = recordSql("Z4 Nomenclature DETAIL max", () ->
            readService.detail(new RestDetailQuery("nomenclature", 1,
                List.of("id", "code", "name", "typeNom"), fixture.nomenclatureId())));
        assertDetailBudget(nomMax, List.of("id", "code", "name", "typeNom"), "Nomenclature max DETAIL");
        assertThat(nomMax.result().data()).containsEntry("typeNom", fixture.nomenclatureTypeNom());

        SqlRead<Throwable> nomMissing = recordSql("Z4 Nomenclature DETAIL missing", () -> catchThrowable(() ->
            readService.detail(new RestDetailQuery("nomenclature", 1, List.of("id"), Long.MAX_VALUE))));
        assertThat(nomMissing.result()).isInstanceOf(org.ipro.rest.service.RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.DETAIL_NOT_FOUND);
        assertDetailLookupBudget(nomMissing, "Nomenclature missing DETAIL");

        // Чтение scalar-результата вне транзакции не выполняет SQL.
        SqlRead<Object> consumeDetail = recordSql("Z4 scalar DETAIL consumption", () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            specMax.result().data().forEach((key, value) -> { });
            return null;
        });
        assertThat(consumeDetail.statements()).isEmpty();

        SqlRead<RestPageResult> page = recordSql("Z4 LIST page for scalar consumption", () ->
            readService.list(new RestListQuery("specifications", 1,
                List.of("id", "codeSpec", "nomenclatureCode"),
                Map.of("journalId", fixture.journalAId()), null, null, 0, 50)));
        SqlRead<Object> consumeList = recordSql("Z4 scalar LIST consumption", () -> {
            page.result().content().forEach(row -> row.forEach((key, value) -> { }));
            return null;
        });
        assertThat(consumeList.statements()).isEmpty();
        assertReturnedOutsideTransaction(page.result().content());
    }

    @Test
    @DisplayName("З4: ролевые grants — short-circuit, бюджет не зависит от size и числа ролей")
    void z4RoleGrantBudgetsShortCircuitAndMultiRole() {
        Fixture fixture = persistFixture();
        addSpecificationsForJournal(fixture, 49);
        addNomenclaturesForType(fixture, 49);

        // Прямой эталон.
        grantPrdSpecRead(subject, fixture.journalAId());
        grantNomenclatureRead(subject);
        readService.list(specMaxQuery(fixture, 1));
        readService.list(nomMaxQuery(fixture, 1));
        SqlRead<RestPageResult> directSpec = recordSql("Z4 direct PrdSpec LIST",
            () -> readService.list(specMaxQuery(fixture, 50)));
        SqlRead<RestPageResult> directNom = recordSql("Z4 direct Nomenclature LIST",
            () -> readService.list(nomMaxQuery(fixture, 50)));
        assertDirectListBudget(directSpec, 50, 50, 50L, "direct PrdSpec LIST");
        assertDirectListBudget(directNom, 50, 50, 50L, "direct Nomenclature LIST");
        assertThat(directSpec.count(SqlKind.ROLE_RESOLUTION)).isZero();
        assertThat(directNom.count(SqlKind.ROLE_RESOLUTION)).isZero();

        // Прямая выдача короткозамыкает до запроса ролей: роль заведена и выдана, но не спрашивается.
        String directWinsUser = "rest-z4-direct-" + token();
        Role directWinsRole = createRole("z4-direct-role-" + token());
        assignRoles(directWinsUser, directWinsRole);
        grantPrdSpecRead(directWinsUser, fixture.journalAId());
        grantNomenclatureRead(directWinsUser);
        grantRoleRead(directWinsRole.getName(), fixture.journalAId());
        loginAs(directWinsUser);
        readService.list(specMaxQuery(fixture, 1));
        readService.list(nomMaxQuery(fixture, 1));
        SqlRead<RestPageResult> directWinsSpec = recordSql("Z4 direct+role PrdSpec LIST",
            () -> readService.list(specMaxQuery(fixture, 50)));
        SqlRead<RestPageResult> directWinsNom = recordSql("Z4 direct+role Nomenclature LIST",
            () -> readService.list(nomMaxQuery(fixture, 50)));
        assertDirectListBudget(directWinsSpec, 50, 50, 50L, "direct+role PrdSpec LIST");
        assertDirectListBudget(directWinsNom, 50, 50, 50L, "direct+role Nomenclature LIST");
        assertThat(directWinsSpec.count(SqlKind.ROLE_RESOLUTION))
            .as("USER grant short-circuit").isZero();
        assertThat(directWinsNom.count(SqlKind.ROLE_RESOLUTION)).isZero();
        assertThat(directWinsSpec.count(SqlKind.C5_GRANT)).isEqualTo(directSpec.count(SqlKind.C5_GRANT));
        assertThat(directWinsNom.count(SqlKind.C5_GRANT)).isEqualTo(directNom.count(SqlKind.C5_GRANT));

        // Роль без прямых грантов.
        String roleUser = "rest-z4-role-" + token();
        Role grantingRole = createRole("z4-grant-role-" + token());
        assignRoles(roleUser, grantingRole);
        grantRoleRead(grantingRole.getName(), fixture.journalAId());
        loginAs(roleUser);
        readService.list(specMaxQuery(fixture, 1));
        readService.list(nomMaxQuery(fixture, 1));
        SqlRead<RestPageResult> oneRoleSpecSize1 = recordSql("Z4 role PrdSpec LIST size=1",
            () -> readService.list(specMaxQuery(fixture, 1)));
        SqlRead<RestPageResult> oneRoleSpecSize50 = recordSql("Z4 role PrdSpec LIST size=50",
            () -> readService.list(specMaxQuery(fixture, 50)));
        SqlRead<RestPageResult> oneRoleNom = recordSql("Z4 role Nomenclature LIST",
            () -> readService.list(nomMaxQuery(fixture, 50)));
        assertListBudget(oneRoleSpecSize1, 1, 1, 50L, "role PrdSpec LIST size 1");
        assertListBudget(oneRoleSpecSize50, 50, 50, 50L, "role PrdSpec LIST size 50");
        assertListBudget(oneRoleNom, 50, 50, 50L, "role Nomenclature LIST");
        assertThat(oneRoleSpecSize1.count(SqlKind.C5_GRANT))
            .isEqualTo(oneRoleSpecSize50.count(SqlKind.C5_GRANT));
        assertThat(oneRoleSpecSize50.count(SqlKind.ROLE_RESOLUTION)).isPositive();
        assertThat(oneRoleSpecSize50.count(SqlKind.C5_GRANT))
            .isGreaterThan(directSpec.count(SqlKind.C5_GRANT));

        // Вторая разрешающая роль: short-circuit на первой — бюджет не удваивается.
        Role secondRole = createRole("z4-grant-role-2-" + token());
        grantRoleRead(secondRole.getName(), fixture.journalAId());
        assignRoles(roleUser, grantingRole, secondRole);
        loginAs(roleUser);
        // Сохранение grant инвалидирует session RLS ids: прогреваем кеш до измерения.
        readService.list(specMaxQuery(fixture, 1));
        readService.list(nomMaxQuery(fixture, 1));
        SqlRead<RestPageResult> twoRolesSpec = recordSql("Z4 two roles PrdSpec LIST",
            () -> readService.list(specMaxQuery(fixture, 50)));
        SqlRead<RestPageResult> twoRolesNom = recordSql("Z4 two roles Nomenclature LIST",
            () -> readService.list(nomMaxQuery(fixture, 50)));
        assertListBudget(twoRolesSpec, 50, 50, 50L, "two roles PrdSpec LIST");
        assertListBudget(twoRolesNom, 50, 50, 50L, "two roles Nomenclature LIST");
        assertThat(twoRolesSpec.count(SqlKind.C5_GRANT))
            .as("short-circuit на первой разрешающей роли")
            .isEqualTo(oneRoleSpecSize50.count(SqlKind.C5_GRANT));
        assertThat(twoRolesNom.count(SqlKind.C5_GRANT)).isEqualTo(oneRoleNom.count(SqlKind.C5_GRANT));
    }

    @Test
    @DisplayName("З4: maximum graph LIST/DETAIL обоих roots без незаявленной загрузки")
    void z4MaximumGraphLoadsOnlyDeclaredAssociations() {
        Fixture fixture = persistFixture();
        Long groupNomId = RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            String groupToken = token();
            GroupNom group = writeExecutor.save(GroupNom.class,
                new GroupNom("G" + groupToken, "Group " + groupToken));
            Nomenclature nomenclature = nomenclatureRepository.findById(fixture.nomenclatureId()).orElseThrow();
            nomenclature.setGroupNom(group);
            nomenclatureRepository.saveAndFlush(nomenclature);
            return group.getId();
        });
        assertThat(groupNomId).isNotNull();
        grantPrdSpecRead(subject, fixture.journalAId());
        grantNomenclatureRead(subject);

        var specifications = catalog.find("specifications", 1).orElseThrow();
        var nomenclatureResource = catalog.find("nomenclature", 1).orElseThrow();
        assertThat(specifications.fixedFetchPaths(RestReadOperation.LIST))
            .containsExactly("id", "codeSpec", "draft", "nomenclature.code");
        assertThat(specifications.fixedFetchPaths(RestReadOperation.DETAIL))
            .containsExactly("id", "codeSpec", "draft", "comment", "journal.id", "nomenclature.code");
        assertThat(nomenclatureResource.fixedFetchPaths(RestReadOperation.LIST))
            .containsExactly("id", "code", "name", "typeNom");
        assertThat(nomenclatureResource.fixedFetchPaths(RestReadOperation.DETAIL))
            .containsExactly("id", "code", "name", "typeNom");

        SpecGraph listGraph = inspectSpecGraph(fixture.specAId(), RestReadOperation.LIST);
        System.out.printf("REST-STAGE3 GRAPH-LOAD PrdSpec LIST %s%n", listGraph);
        assertThat(listGraph.idLoaded()).isTrue();
        assertThat(listGraph.nomenclatureLoaded()).isTrue();
        assertThat(listGraph.nomenclatureCodeLoaded()).isTrue();
        assertThat(listGraph.nomenclatureUnitLoaded())
            .as("unitOfMeasurement цели не заявлен maximum LIST").isFalse();
        assertThat(listGraph.nomenclatureGroupNomLoaded())
            .as("groupNom цели не заявлен maximum LIST").isFalse();
        assertThat(listGraph.journalLoaded()).as("journal не заявлен maximum LIST").isFalse();
        assertThat(listGraph.materialsLoaded()).isFalse();
        assertThat(listGraph.operationsLoaded()).isFalse();

        // journal.id — explicit subgraph с terminal id: association инициализируется, а
        // дополнительная материализация скалярных колонок joined-строки разрешена ADR-0007
        // (граф описывает required state, не список SQL columns); своих associations у
        // Journal нет вовсе, поэтому loaded-state gate проверяется по nomenclature-цели.
        SpecGraph detailGraph = inspectSpecGraph(fixture.specAId(), RestReadOperation.DETAIL);
        System.out.printf("REST-STAGE3 GRAPH-LOAD PrdSpec DETAIL %s%n", detailGraph);
        assertThat(detailGraph.idLoaded()).isTrue();
        assertThat(detailGraph.journalLoaded()).as("journal.id terminal").isTrue();
        assertThat(detailGraph.journalIdLoaded()).isTrue();
        assertThat(detailGraph.journalIdValue()).as("identity journal").isEqualTo(fixture.journalAId());
        assertThat(detailGraph.nomenclatureLoaded()).isTrue();
        assertThat(detailGraph.nomenclatureCodeLoaded()).isTrue();
        assertThat(detailGraph.nomenclatureUnitLoaded()).isFalse();
        assertThat(detailGraph.nomenclatureGroupNomLoaded()).isFalse();
        assertThat(detailGraph.materialsLoaded()).isFalse();
        assertThat(detailGraph.operationsLoaded()).isFalse();

        NomGraph nomListGraph = inspectNomGraph(fixture.nomenclatureId(), RestReadOperation.LIST);
        System.out.printf("REST-STAGE3 GRAPH-LOAD Nomenclature LIST %s%n", nomListGraph);
        assertThat(nomListGraph.idLoaded()).isTrue();
        assertThat(nomListGraph.unitLoaded()).isFalse();
        assertThat(nomListGraph.groupNomLoaded()).as("groupNom непустой, но не заявлен").isFalse();

        NomGraph nomDetailGraph = inspectNomGraph(fixture.nomenclatureId(), RestReadOperation.DETAIL);
        System.out.printf("REST-STAGE3 GRAPH-LOAD Nomenclature DETAIL %s%n", nomDetailGraph);
        assertThat(nomDetailGraph.idLoaded()).isTrue();
        assertThat(nomDetailGraph.unitLoaded()).isFalse();
        assertThat(nomDetailGraph.groupNomLoaded()).isFalse();
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

    private static RestListQuery specMaxQuery(Fixture fixture, int size) {
        return new RestListQuery("specifications", 1,
            List.of("id", "codeSpec", "draft", "nomenclatureCode"),
            Map.of("journalId", fixture.journalAId()), null, null, 0, size);
    }

    private static RestListQuery nomMaxQuery(Fixture fixture, int size) {
        return new RestListQuery("nomenclature", 1,
            List.of("id", "code", "name", "typeNom"),
            Map.of("typeNom", fixture.nomenclatureTypeNom()), null, null, 0, size);
    }

    /** Bulk fixture DML (H2-only) keeps setup outside the measured Hibernate read path. */
    private void addNomenclaturesForType(Fixture fixture, int amount) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
            assertThat(jdbcTemplate.update("""
                insert into nomenclature (code, name, type_nom, unit_id, version)
                select concat(?, cast(x as varchar)), 'Budget fixture', ?, ?, 0 from system_range(1, ?)
                """, "ZN" + token, fixture.nomenclatureTypeNom(), fixture.unitOfMeasurementId(), amount))
                .isEqualTo(amount));
    }

    /**
     * LIST business budget: ровно один content. Count — не более одного и обязателен на
     * полной странице: Spring Data пропускает count для неполной страницы, но total в
     * этом случае совпадает с content. Когда total контролируется фикстурой, он сверяется
     * ({@code expectedTotal}); для неконтролируемого общего количества передаётся null.
     */
    private void assertListBudget(SqlRead<RestPageResult> read, int expectedRows, int requestedSize,
                                  Long expectedTotal, String label) {
        assertThat(read.result().content()).as(label).hasSize(expectedRows);
        if (expectedTotal != null) {
            assertThat(read.result().totalElements()).as(label).isEqualTo(expectedTotal);
        }
        assertThat(read.count(SqlKind.CONTENT)).as(label).isEqualTo(1);
        long countQueries = read.count(SqlKind.COUNT);
        assertThat(countQueries).as(label + " count").isBetween(0L, 1L);
        if (expectedRows == requestedSize) {
            assertThat(countQueries).as(label + " full page выполняет count").isEqualTo(1);
        }
        assertThat(read.count(SqlKind.OTHER)).as(label).isZero();
        assertThat(read.count(SqlKind.RLS_GRANT)).as(label).isZero();
        assertReturnedOutsideTransaction(read.result().content());
    }

    /** Direct-грант не должен разрешать роли: отдельная ветка бюджета для USER-грантов. */
    private void assertDirectListBudget(SqlRead<RestPageResult> read, int expectedRows, int requestedSize,
                                        Long expectedTotal, String label) {
        assertListBudget(read, expectedRows, requestedSize, expectedTotal, label);
        assertThat(read.count(SqlKind.ROLE_RESOLUTION)).as(label).isZero();
    }

    private void assertConstantSecurityBudget(String label, List<Long> c5Counts) {
        assertThat(c5Counts).as(label).hasSize(3);
        assertThat(c5Counts.get(0)).as(label + " C5 queries").isPositive();
        assertThat(c5Counts.get(1)).as(label + " size 50 не меняет C5").isEqualTo(c5Counts.get(0));
        assertThat(c5Counts.get(2)).as(label + " size 200 не меняет C5").isEqualTo(c5Counts.get(0));
    }

    private void assertDetailBudget(SqlRead<RestDetailResult> read, List<String> expectedKeys,
                                    String label) {
        assertThat(read.result().data().keySet()).as(label).containsExactlyElementsOf(expectedKeys);
        assertThat(read.count(SqlKind.CONTENT)).as(label).isEqualTo(1);
        assertThat(read.count(SqlKind.COUNT)).as(label).isZero();
        assertThat(read.count(SqlKind.OTHER)).as(label).isZero();
        assertThat(read.count(SqlKind.RLS_GRANT)).as(label).isZero();
        assertThat(read.count(SqlKind.ROLE_RESOLUTION)).as(label).isZero();
        assertReturnedOutsideTransaction(List.of(read.result().data()));
    }

    private void assertDetailLookupBudget(SqlRead<Throwable> read, String label) {
        assertThat(read.count(SqlKind.CONTENT)).as(label).isEqualTo(1);
        assertThat(read.count(SqlKind.COUNT)).as(label).isZero();
        assertThat(read.count(SqlKind.OTHER)).as(label).isZero();
        assertThat(read.count(SqlKind.RLS_GRANT)).as(label).isZero();
        assertThat(read.count(SqlKind.ROLE_RESOLUTION)).as(label).isZero();
    }

    private SpecGraph inspectSpecGraph(Long specId, RestReadOperation operation) {
        var resource = catalog.find("specifications", 1).orElseThrow();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            Specification<PrdSpec> byId = (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("id"), specId);
            PrdSpec entity;
            if (operation == RestReadOperation.LIST) {
                entity = entityReadAccess.list(PrdSpec.class, byId, PageRequest.of(0, 1),
                    resource.fixedFetchPaths(operation)).getContent().getFirst();
            } else {
                entity = entityReadAccess.detail(PrdSpec.class, specId, resource.fixedFetchPaths(operation))
                    .orElseThrow();
            }
            PersistenceUnitUtil persistence = entityManagerFactory.getPersistenceUnitUtil();
            boolean nomenclatureLoaded = persistence.isLoaded(entity, "nomenclature");
            boolean journalLoaded = persistence.isLoaded(entity, "journal");
            boolean journalIdLoaded = false;
            boolean journalCodeLoaded = false;
            Long journalIdValue = null;
            if (journalLoaded) {
                journalIdLoaded = persistence.isLoaded(entity.getJournal(), "id");
                journalCodeLoaded = persistence.isLoaded(entity.getJournal(), "code");
                journalIdValue = entity.getJournal().getId();
            }
            return new SpecGraph(persistence.isLoaded(entity, "id"),
                nomenclatureLoaded,
                nomenclatureLoaded && persistence.isLoaded(entity.getNomenclature(), "code"),
                nomenclatureLoaded && persistence.isLoaded(entity.getNomenclature(), "unitOfMeasurement"),
                nomenclatureLoaded && persistence.isLoaded(entity.getNomenclature(), "groupNom"),
                journalLoaded, journalIdLoaded, journalCodeLoaded, journalIdValue,
                persistence.isLoaded(entity, "materials"),
                persistence.isLoaded(entity, "operations"));
        });
    }

    private NomGraph inspectNomGraph(Long id, RestReadOperation operation) {
        var resource = catalog.find("nomenclature", 1).orElseThrow();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            Specification<Nomenclature> byId = (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("id"), id);
            Nomenclature entity;
            if (operation == RestReadOperation.LIST) {
                entity = entityReadAccess.list(Nomenclature.class, byId, PageRequest.of(0, 1),
                    resource.fixedFetchPaths(operation)).getContent().getFirst();
            } else {
                entity = entityReadAccess.detail(Nomenclature.class, id, resource.fixedFetchPaths(operation))
                    .orElseThrow();
            }
            PersistenceUnitUtil persistence = entityManagerFactory.getPersistenceUnitUtil();
            return new NomGraph(persistence.isLoaded(entity, "id"),
                persistence.isLoaded(entity, "unitOfMeasurement"),
                persistence.isLoaded(entity, "groupNom"));
        });
    }

    private static String token() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private void grantNomenclatureRead(String username) {
        grantForSubject(AccessGrant.SubjectType.USER, username, nomenclatureReadKeys());
    }

    private void grantPrdSpecRead(String username, Long journalId) {
        grantForSubject(AccessGrant.SubjectType.USER, username, prdSpecReadKeys());
        grantRow(AccessGrant.SubjectType.USER, username, journalId);
    }

    /** Полный read-набор роли для обоих ресурсов: те же ключи, что у прямых грантов предмета. */
    private void grantRoleRead(String roleName, Long journalId) {
        grantForSubject(AccessGrant.SubjectType.ROLE, roleName, prdSpecReadKeys());
        grantForSubject(AccessGrant.SubjectType.ROLE, roleName, nomenclatureReadKeys());
        grantRow(AccessGrant.SubjectType.ROLE, roleName, journalId);
    }

    private static String[] prdSpecReadKeys() {
        return new String[]{
            "REST:specifications:v1:LIST", "REST:specifications:v1:DETAIL",
            C5PermissionKeys.entityRead(PrdSpec.class),
            C5PermissionKeys.attributeRead(PrdSpec.class, "id"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "codeSpec"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "draft"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "comment"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "nomenclature"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "journal"),
            C5PermissionKeys.entityRead(Nomenclature.class),
            C5PermissionKeys.attributeRead(Nomenclature.class, "code"),
            C5PermissionKeys.entityRead(Journal.class),
            C5PermissionKeys.attributeRead(Journal.class, "id")};
    }

    private static String[] nomenclatureReadKeys() {
        return new String[]{
            "REST:nomenclature:v1:LIST",
            "REST:nomenclature:v1:DETAIL",
            C5PermissionKeys.entityRead(Nomenclature.class),
            C5PermissionKeys.attributeRead(Nomenclature.class, "id"),
            C5PermissionKeys.attributeRead(Nomenclature.class, "code"),
            C5PermissionKeys.attributeRead(Nomenclature.class, "name"),
            C5PermissionKeys.attributeRead(Nomenclature.class, "typeNom")};
    }

    private void grant(String username, String... dimensions) {
        grantForSubject(AccessGrant.SubjectType.USER, username, dimensions);
    }

    private void grantForSubject(AccessGrant.SubjectType subjectType, String subjectKey,
                                 String... dimensions) {
        for (String dimension : dimensions) {
            AccessGrant grant = new AccessGrant();
            grant.setSubjectType(subjectType);
            grant.setSubjectKey(subjectKey);
            grant.setDimension(dimension);
            grant.setDimensionValueId(null);
            grant.setCanRead(true);
            accessGrantRepository.save(grant);
        }
        accessGrantRepository.flush();
    }

    private void grantRow(AccessGrant.SubjectType subjectType, String subjectKey, Long journalId) {
        AccessGrant rowGrant = new AccessGrant();
        rowGrant.setSubjectType(subjectType);
        rowGrant.setSubjectKey(subjectKey);
        rowGrant.setDimension("JOURNAL");
        rowGrant.setDimensionValueId(journalId);
        rowGrant.setCanRead(true);
        accessGrantRepository.saveAndFlush(rowGrant);
    }

    private Role createRole(String name) {
        return RlsTestFixture.callAsSuperuser(accessGrantRepository, () ->
            roleRepository.saveAndFlush(new Role(name)));
    }

    private void assignRoles(String username, Role... roles) {
        RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            User user = userRepository.findByUsername(username)
                .orElseGet(() -> new User(username, "password"));
            user.setRoles(Set.of(roles));
            return userRepository.saveAndFlush(user);
        });
    }

    private record Fixture(Long nomenclatureId, String nomenclatureCode, String nomenclatureName,
                           String nomenclatureTypeNom, Long unitOfMeasurementId,
                           Long journalAId, Long journalBId, Long specAId, String specACode,
                           Long specBId, String specBCode) {
    }

    private record LoadedAssociations(boolean nomenclatureLoaded, boolean nomenclatureCodeLoaded,
                                      boolean journalLoaded, boolean journalIdLoaded) {
    }

    private record SpecGraph(boolean idLoaded, boolean nomenclatureLoaded, boolean nomenclatureCodeLoaded,
                             boolean nomenclatureUnitLoaded, boolean nomenclatureGroupNomLoaded,
                             boolean journalLoaded, boolean journalIdLoaded, boolean journalCodeLoaded,
                             Long journalIdValue, boolean materialsLoaded, boolean operationsLoaded) {
    }

    private record NomGraph(boolean idLoaded, boolean unitLoaded, boolean groupNomLoaded) {
    }
}
