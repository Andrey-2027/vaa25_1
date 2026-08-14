package org.ipro.reportstudio.param;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.UnitOfMeasurement;
import org.ip.repository.UserRepository;
import org.ip.security.CurrentUser;
import org.ip.security.UserRepositoryRlsRoleResolver;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.dom.ReportComputedValue;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.query.GuardResult;
import org.ipro.reportstudio.query.ReportQueryExecutor;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;
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
 * Сквозной цикл Фазы 3 на H2 с настоящими RLS-фильтрами: guard (entityClass
 * параметра) → resolve (включая строгий RLS-перезапрос) → execute.
 * <p>
 * Ключевые сценарии: alice не может передать :journal, который ей недоступен
 * (перезапрос вернёт пусто — «не найдена или недоступна»), admin — может;
 * COMPUTED(CURRENT_USER) резолвится из контекста; весь цикл даёт только строки
 * разрешённого журнала.
 */
@DataJpaTest
@EnableJpaRepositories(basePackages = {"org.ip", "org.ipro.rls"})
@ContextConfiguration(classes = Application.class)
class ReportParamResolutionIT {

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
    private ReportParamResolver resolver;

    private Long journalAId;
    private Long journalBId;

    @BeforeEach
    void setUp() {
        var registry = new RlsDimensionRegistry("org.ip");
        registry.rebuild();
        var accessService = new AccessService(accessGrantRepository,
            new UserRepositoryRlsRoleResolver(userRepository), registry);
        var cache = new RlsReadableIdsCache(accessService);
        activator = new RlsFilterActivator(registry, cache, () -> CurrentUser.username());
        var readGate = new RlsReadGate(accessService, registry);

        var analyzer = new SqmQuerySemanticAnalyzer(entityManagerFactory);
        guard = new ReportQueryGuard(analyzer, accessService, registry,
            () -> CurrentUser.username(), entityManagerFactory);
        executor = new ReportQueryExecutor(entityManager, activator);

        var refresher = new EntityParamRefresher(entityManager, activator, readGate,
            () -> CurrentUser.username(), entityManagerFactory
            .unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class));
        resolver = new ReportParamResolver(refresher, accessService, () -> CurrentUser.username(),
            entityManagerFactory.unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class),
            new ObjectMapper(), "BRANCH");

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
        journalBId = journalB.getId();

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
    void aliceCanBindJournalSheHasAccessTo() {
        loginAs("alice");
        ReportParam journal = entityParam("journal", ReportParamKind.ENTITY, ReportParamSource.FORM, true);

        ResolvedParams resolved = resolver.resolve(List.of(journal),
            ReportContext.empty(CurrentUser.username()), Map.of("journal", journalAId));

        assertThat(resolved.ok()).isTrue();
        Object value = resolved.bindings().get("journal");
        assertThat(value).isInstanceOf(Journal.class);
        assertThat(((Journal) value).getId()).isEqualTo(journalAId);
    }

    @Test
    void aliceCannotBindJournalOutOfHerAccess() {
        loginAs("alice");
        ReportParam journal = entityParam("journal", ReportParamKind.ENTITY, ReportParamSource.FORM, true);

        ResolvedParams resolved = resolver.resolve(List.of(journal),
            ReportContext.empty(CurrentUser.username()), Map.of("journal", journalBId));

        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.errors())
            .anyMatch(e -> e.contains(":journal") && e.contains("id=" + journalBId)
                && e.contains("RLS"));
    }

    @Test
    void adminWithWildcardCanBindAnyJournal() {
        loginAs("admin");
        ReportParam journal = entityParam("journal", ReportParamKind.ENTITY, ReportParamSource.FORM, true);

        ResolvedParams resolved = resolver.resolve(List.of(journal),
            ReportContext.empty(CurrentUser.username()), Map.of("journal", journalBId));

        assertThat(resolved.ok()).isTrue();
        assertThat(((Journal) resolved.bindings().get("journal")).getId()).isEqualTo(journalBId);
    }

    @Test
    void computedCurrentUserResolvesToSecurityUser() {
        loginAs("alice");
        ReportParam who = scalarParam("who", ReportParamSource.COMPUTED, true);
        who.setComputed(ReportComputedValue.CURRENT_USER);

        ResolvedParams resolved = resolver.resolve(List.of(who),
            ReportContext.empty(CurrentUser.username()), Map.of());

        assertThat(resolved.ok()).isTrue();
        assertThat(resolved.bindings()).containsEntry("who", "alice");
    }

    @Test
    void fullCycleGuardResolveExecuteReturnsOnlyAccessibleRows() {
        loginAs("alice");
        ReportParam journal = entityParam("journal", ReportParamKind.ENTITY, ReportParamSource.FORM, true);
        String jpql = "select s.codeSpec from PrdSpec s where s.journal = :journal";

        GuardResult guardResult = guard.guard(jpql, Set.of("journal"),
            Map.of("journal", Journal.class));
        assertThat(guardResult.allowed()).isTrue();

        ResolvedParams resolved = resolver.resolve(List.of(journal),
            ReportContext.empty(CurrentUser.username()), Map.of("journal", journalAId));
        assertThat(resolved.ok()).isTrue();

        ReportDataset dataset = executor.execute(jpql, resolved.bindings(),
            guardResult.selectFields(), PREVIEW_MAX_ROWS, 30_000);

        assertThat(dataset.rows())
            .extracting(r -> r.value("s.codeSpec"))
            .containsExactly("SPEC-A");
    }

    @Test
    void fullCycleDeniedWhenEntityParamIsOutOfAccess() {
        loginAs("alice");
        ReportParam journal = entityParam("journal", ReportParamKind.ENTITY, ReportParamSource.FORM, true);
        String jpql = "select s.codeSpec from PrdSpec s where s.journal = :journal";

        GuardResult guardResult = guard.guard(jpql, Set.of("journal"),
            Map.of("journal", Journal.class));
        assertThat(guardResult.allowed()).isTrue();

        ResolvedParams resolved = resolver.resolve(List.of(journal),
            ReportContext.empty(CurrentUser.username()), Map.of("journal", journalBId));

        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.errors()).anyMatch(e -> e.contains(":journal"));
    }

    @Test
    void entityListParamRefusesUnavailableElement() {
        loginAs("alice");
        ReportParam journals = entityParam("journals", ReportParamKind.ENTITY_LIST,
            ReportParamSource.FORM, true);

        ResolvedParams resolved = resolver.resolve(List.of(journals),
            ReportContext.empty(CurrentUser.username()),
            Map.of("journals", List.of(journalAId, journalBId)));

        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.errors())
            .anyMatch(e -> e.contains(":journals") && e.contains("id=" + journalBId));
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

    private static ReportParam entityParam(String name, ReportParamKind kind,
                                           ReportParamSource source, boolean required) {
        ReportParam p = new ReportParam();
        p.setName(name);
        p.setKind(kind);
        p.setValueSource(source);
        p.setRequired(required);
        p.setEntityClass(Journal.class.getName());
        return p;
    }

    private static ReportParam scalarParam(String name, ReportParamSource source, boolean required) {
        ReportParam p = new ReportParam();
        p.setName(name);
        p.setKind(ReportParamKind.SCALAR);
        p.setValueSource(source);
        p.setRequired(required);
        return p;
    }
}