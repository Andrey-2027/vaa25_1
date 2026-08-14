package org.ipro.reportstudio.query;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.model.Journal;
import org.ip.repository.UserRepository;
import org.ip.security.CurrentUser;
import org.ip.security.UserRepositoryRlsRoleResolver;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
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
 * Гейт выполнения JPQL отчёта (Фаза 2): SELECT-only, двусторонний :param,
 * RLS entity-access по измерениям сущностей запроса. Сборка RLS-бинов —
 * вручную, как в RlsIntegrationTest (контекст без Vaadin/сервисов).
 */
@DataJpaTest
@EnableJpaRepositories(basePackages = {"org.ip", "org.ipro.rls"})
@ContextConfiguration(classes = Application.class)
class ReportQueryGuardTest {

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private UserRepository userRepository;

    private RlsDimensionRegistry registry;
    private AccessService accessService;
    private ReportQueryGuard guard;

    @BeforeEach
    void setUp() {
        registry = new RlsDimensionRegistry("org.ip");
        registry.rebuild();
        accessService = new AccessService(accessGrantRepository,
            new UserRepositoryRlsRoleResolver(userRepository), registry);
        RlsCurrentUser currentUser = () -> CurrentUser.username();

        var analyzer = new org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer(entityManagerFactory);
        guard = new ReportQueryGuard(analyzer, accessService, registry, currentUser, entityManagerFactory);

        // alice: чтение журнала A; admin: wildcard на всё
        org.ip.model.Journal journalA = new org.ip.model.Journal();
        journalA.setCode("A");
        journalA.setName("Журнал A");
        entityManager.persist(journalA);
        entityManager.flush();

        persistGrant("alice", "JOURNAL", journalA.getId(), true);
        persistGrant("admin", "*", null, true);
        entityManager.flush();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void selectIsAllowed() {
        loginAs("admin");
        GuardResult result = guard.guard("select j.id from Journal j", Set.of());
        assertThat(result.allowed()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void updateIsDenied() {
        loginAs("admin");
        GuardResult result = guard.guard("update Journal j set j.name = 'x'", Set.of());
        assertThat(result.allowed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("только SELECT"));
    }

    @Test
    void undeclaredParameterIsDenied() {
        loginAs("admin");
        GuardResult result = guard.guard(
            "select j.id from Journal j where j.code = :code", Set.of("other"));
        assertThat(result.allowed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains(":code"));
    }

    @Test
    void declaredParameterIsAllowed() {
        loginAs("admin");
        GuardResult result = guard.guard(
            "select j.id from Journal j where j.code = :code", Set.of("code", "unused"));
        assertThat(result.allowed()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.contains("unused"));
    }

    @Test
    void userWithoutGrantsIsDeniedForProtectedEntity() {
        loginAs("bob"); // нет ни одного AccessGrant
        GuardResult result = guard.guard("select j.id from Journal j", Set.of());
        assertThat(result.allowed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("JOURNAL") && e.contains("Journal"));
    }

    @Test
    void wildcardGrantAllowsEverything() {
        loginAs("admin");
        GuardResult result = guard.guard(
            "select s.id from PrdSpec s join s.journal j", Set.of());
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void unguardedEntityIsAllowedForAnyone() {
        // Nomenclature — без @RlsDimension: RLS-проверка не требуется
        loginAs("bob");
        GuardResult result = guard.guard(
            "select n.id from Nomenclature n", Set.of());
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void protectedEntityViaSubqueryIsChecked() {
        loginAs("bob");
        GuardResult result = guard.guard(
            "select n.id from Nomenclature n "
                + "where exists (select s.id from PrdSpec s where s.nomenclature = n)", Set.of());
        assertThat(result.allowed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("PrdSpec"));
    }

    @Test
    void entityParamWithGrantIsAllowed() {
        loginAs("alice"); // чтение Journal A есть
        GuardResult result = guard.guard(
            "select s.codeSpec from PrdSpec s where s.journal = :journal",
            Set.of("journal"), Map.of("journal", Journal.class));
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void entityParamWithoutGrantIsDenied() {
        loginAs("bob"); // грантов нет — сущность входит только через параметр
        GuardResult result = guard.guard(
            "select s.codeSpec from PrdSpec s where s.journal = :journal",
            Set.of("journal"), Map.of("journal", Journal.class));
        assertThat(result.allowed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("JOURNAL") && e.contains("Journal"));
    }

    @Test
    void unusedEntityParamIsNotChecked() {
        // параметр объявлен в шаблоне, но запросом не используется — RLS-проверки не требует
        loginAs("bob");
        GuardResult result = guard.guard(
            "select n.id from Nomenclature n",
            Set.of("journal"), Map.of("journal", Journal.class));
        assertThat(result.allowed()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.contains("journal"));
    }

    @Test
    void unknownEntityParamClassIsDenied() {
        loginAs("admin");
        GuardResult result = guard.guard(
            "select s.codeSpec from PrdSpec s where s.codeSpec = :code",
            Set.of("code"), Map.of("code", java.lang.String.class));
        assertThat(result.allowed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains(":code") && e.contains("не является сущностью"));
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