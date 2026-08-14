package org.ipro.reportstudio.query;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.UnitOfMeasurement;
import org.ip.repository.UserRepository;
import org.ip.security.CurrentUser;
import org.ip.security.UserRepositoryRlsRoleResolver;
import org.ipro.reportstudio.data.EntityRef;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.data.ReportRow;
import org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadableIdsCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозной прогон Фазы 2: guard + executor против настоящего Hibernate
 * @Filter на H2. RLS-бина собираются вручную, как в RlsIntegrationTest;
 * executor применяет ту же обвязку (RlsFilterActivator.ensureRlsEnabled),
 * что ListForm.
 */
@DataJpaTest
@EnableJpaRepositories(basePackages = {"org.ip", "org.ipro.rls"})
@ContextConfiguration(classes = Application.class)
class ReportQueryExecutionIT {

    private static final int PREVIEW_MAX_ROWS = 20;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private UserRepository userRepository;

    private RlsFilterActivator activator;
    private ReportQueryGuard guard;
    private ReportQueryExecutor executor;

    private Long journalAId;

    @BeforeEach
    void setUp() {
        var registry = new RlsDimensionRegistry("org.ip");
        registry.rebuild();
        var accessService = new AccessService(accessGrantRepository,
            new UserRepositoryRlsRoleResolver(userRepository), registry);
        var cache = new RlsReadableIdsCache(accessService);
        activator = new RlsFilterActivator(registry, cache, () -> CurrentUser.username());

        var analyzer = new SqmQuerySemanticAnalyzer(entityManagerFactory);
        guard = new ReportQueryGuard(analyzer, accessService, registry,
            () -> CurrentUser.username(), entityManagerFactory);
        executor = new ReportQueryExecutor(entityManager, activator);

        Journal journalA = new Journal();
        journalA.setCode("A");
        journalA.setName("Журнал A");
        entityManager.persist(journalA);

        Journal journalB = new Journal();
        journalB.setCode("B");
        journalB.setName("Журнал B");
        entityManager.persist(journalB);
        entityManager.flush();
        journalAId = journalA.getId();

        persistGrant("alice", "JOURNAL", journalAId, true);
        persistGrant("admin", "*", null, true);
        entityManager.flush();

        createPrdSpec(journalA, "SPEC-A");
        createPrdSpec(journalB, "SPEC-B");
        entityManager.flush();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aliceSeesOnlyHerJournalRows() {
        loginAs("alice");
        GuardResult result = guard.guard(
            "select s.codeSpec, s.journal.id, s.journal.name from PrdSpec s order by s.codeSpec",
            Set.of());
        assertThat(result.allowed()).isTrue();

        ReportDataset dataset = executor.execute(
            "select s.codeSpec, s.journal.id, s.journal.name from PrdSpec s order by s.codeSpec",
            Map.of(), result.selectFields(), PREVIEW_MAX_ROWS, 30_000);

        assertThat(dataset.rowCount()).isEqualTo(1);
        assertThat(dataset.rows()[0].value("s.codeSpec")).isEqualTo("SPEC-A");
        assertThat(dataset.rows()[0].value("s.journal.id")).isEqualTo(journalAId);
    }

    @Test
    void entityColumnIsNormalizedToEntityRef() {
        loginAs("admin");
        GuardResult result = guard.guard("select s.codeSpec, s.journal from PrdSpec s", Set.of());
        assertThat(result.allowed()).isTrue();

        ReportDataset dataset = executor.execute(
            "select s.codeSpec, s.journal from PrdSpec s", Map.of(),
            result.selectFields(), PREVIEW_MAX_ROWS, 30_000);

        ReportRow row = dataset.rows()[0];
        EntityRef ref = (EntityRef) row.value("s.journal");
        assertThat(ref.id()).isEqualTo(journalAId);
        assertThat(ref.caption()).isEqualTo("A Журнал A"); // Journal.getDisplayName() = code + " " + name
        assertThat(row.displayValue("s.journal")).contains("Журнал A");
    }

    @Test
    void parameterIsBoundAndFilters() {
        loginAs("admin");
        String jpql = "select s.codeSpec from PrdSpec s where s.journal.code = :code";
        GuardResult result = guard.guard(jpql, Set.of("code"));
        assertThat(result.allowed()).isTrue();

        ReportDataset dataset = executor.execute(jpql, Map.of("code", "B"),
            result.selectFields(), PREVIEW_MAX_ROWS, 30_000);

        assertThat(dataset.field("s.codeSpec").name()).isEqualTo("s.codeSpec");
        assertThat(dataset.rows())
            .extracting(r -> r.value("s.codeSpec"))
            .containsExactly("SPEC-B");
    }

    @Test
    void maxRowsIsEnforced() {
        loginAs("admin");
        String jpql = "select s.codeSpec from PrdSpec s order by s.codeSpec";
        GuardResult result = guard.guard(jpql, Set.of());
        assertThat(result.allowed()).isTrue();

        ReportDataset dataset = executor.execute(jpql, Map.of(),
            result.selectFields(), 1, 30_000);

        assertThat(dataset.rowCount()).isEqualTo(1);
        assertThat(dataset.rows()[0].value("s.codeSpec")).isEqualTo("SPEC-A");
    }

    @Test
    void executionTogetherWithGuardRejectsUpdate() {
        loginAs("admin");
        GuardResult result = guard.guard("update Journal j set j.name = 'x'", Set.of());
        assertThat(result.allowed()).isFalse();
    }

    @Test
    void guardDeniesRowAdditionForUserWithNoGrantsBeforeExecution() {
        loginAs("bob");
        GuardResult result = guard.guard("select s.codeSpec from PrdSpec s", Set.of());
        assertThat(result.allowed()).isFalse();
    }

    private void createPrdSpec(Journal journal, String codeSpec) {
        UnitOfMeasurement unit = new UnitOfMeasurement();
        unit.setCode("U-" + codeSpec);
        unit.setShortCode("SC-" + codeSpec);
        unit.setName("Штука " + codeSpec);
        entityManager.persist(unit);

        Nomenclature nomenclature = new Nomenclature();
        nomenclature.setCode("N-" + codeSpec);
        nomenclature.setName("Деталь " + codeSpec);
        nomenclature.setUnitOfMeasurement(unit);
        entityManager.persist(nomenclature);

        PrdSpec spec = new PrdSpec();
        spec.setJournal(journal);
        spec.setNomenclature(nomenclature);
        spec.setCodeSpec(codeSpec);
        entityManager.persist(spec);
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private void persistGrant(String subjectKey, String dimension, Long dimensionValueId, boolean read) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(subjectKey);
        grant.setDimension(dimension);
        grant.setDimensionValueId(dimensionValueId);
        grant.setCanRead(read);
        grant.setCanUpdate(false);
        grant.setCanDelete(false);
        entityManager.persist(grant);
    }
}