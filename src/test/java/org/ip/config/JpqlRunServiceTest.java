package org.ip.config;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryExecutor;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadableIdsCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.ipro.rls.config.RlsPersistenceAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Единая точка выполнения JPQL ({@link JpqlRunService}): guard -> preview,
 * отказ guard'а, лимит колонок по движку, UReport-семантика имён
 * (точки -> подчёркивания) и EntityRef -> отображаемое значение.
 *
 * Сборка бинов вручную по образцу ReportQueryGuardTest/RlsIntegrationTest —
 * контекст без Vaadin/сервисов.
 */
@DataJpaTest
// org.ip объявлен в Application#@EnableJpaRepositories (иначе дублирование бобов репозиториев в срезе)
@ImportAutoConfiguration(RlsPersistenceAutoConfiguration.class)
@ContextConfiguration(classes = Application.class)
class JpqlRunServiceTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private org.ipro.rls.AccessGrantRepository accessGrantRepository;

    @Autowired
    private org.ip.repository.UserRepository userRepository;

    private JpqlRunService service;

    @BeforeEach
    void setUp() {
        RlsDimensionRegistry registry = new RlsDimensionRegistry("org.ip");
        registry.rebuild();
        AccessService accessService = new AccessService(accessGrantRepository,
                new org.ip.security.UserRepositoryRlsRoleResolver(userRepository), registry);
        RlsCurrentUser currentUser = () -> SecurityContextHolder.getContext()
                .getAuthentication().getName();

        var analyzer = new SqmQuerySemanticAnalyzer(
                entityManager.getEntityManagerFactory());
        ReportQueryGuard guard = new ReportQueryGuard(analyzer, accessService,
                registry, currentUser, entityManager.getEntityManagerFactory());

        RlsReadableIdsCache cache = new RlsReadableIdsCache(accessService);
        RlsFilterActivator activator = new RlsFilterActivator(registry, cache, currentUser);
        ReportQueryExecutor executor = new ReportQueryExecutor(entityManager, activator);
        ReportPreviewService preview = new ReportPreviewService(executor);

        service = new JpqlRunService(guard, preview);

        // admin: wildcard-грант на всё (как в ReportQueryGuardTest)
        persistGrant("admin", "*", null, true);

        org.ip.model.Journal journal = new org.ip.model.Journal();
        journal.setCode("A");
        journal.setName("Журнал A");
        entityManager.persist(journal);
        entityManager.flush();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void runReturnsDatasetForAllowedSelect() {
        loginAs("admin");
        ReportDataset ds = service.run(
                "select j.id as id, j.name as nm from Journal j", Map.of());
        QueryField[] fields = ds.fields();
        assertThat(fields).extracting(QueryField::name).containsExactly("id", "nm");
        assertThat(ds.rowCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void nonSelectIsRejectedWithGuardMessage() {
        loginAs("admin");
        assertThatThrownBy(() -> service.run("update Journal j set j.name = 'x'", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("guard'ом")
                .hasMessageContaining("только SELECT");
    }

    @Test
    void undeclaredParameterIsRejected() {
        loginAs("admin");
        // параметр запроса :code не передан в bindings -> двусторонняя проверка guard'а
        assertThatThrownBy(() -> service.run(
                        "select j.id from Journal j where j.code = :code", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(":code");
    }

    @Test
    void defaultColumnLimitIsStandardForUdrUreport() {
        loginAs("admin");
        String jpql = manyColumnsSelect(ReportQueryGuard.MAX_COLUMNS + 1);
        assertThatThrownBy(() -> service.run(jpql, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("колонок");
    }

    @Test
    void extendedLimitFitsJrReports() {
        loginAs("admin");
        String jpql = manyColumnsSelect(ReportQueryGuard.MAX_COLUMNS + 1);
        // JR-движок передаёт расширенный лимит (Ф1.2): тот же запрос проходит
        ReportDataset ds = service.run(jpql, Map.of(), 50);
        assertThat(ds.fields()).hasSize(ReportQueryGuard.MAX_COLUMNS + 1);
    }

    @Test
    void runAsMapsReplacesDotsInFieldNames() {
        loginAs("admin");
        List<Map<String, Object>> rows =
                service.runAsMaps("select j.name from Journal j where j.code = 'A'", Map.of());
        assertThat(rows).hasSize(1);
        // анализатор без алиаса даёт имя "j.name" — точки заменяются на '_'
        assertThat(rows.get(0)).containsKey("j_name");
        assertThat(rows.get(0)).doesNotContainKeys("j.name");
    }

    @Test
    void runAsMapsConvertsEntityRefToDisplayValue() {
        loginAs("admin");
        List<Map<String, Object>> rows =
                service.runAsMaps("select j from Journal j where j.code = 'A'", Map.of());
        assertThat(rows).hasSize(1);
        Object value = rows.get(0).values().iterator().next();
        // Journal реализует HasDisplayName -> EntityRef разворачивается в caption
        assertThat(value).isEqualTo("A Журнал A");
    }

    private String manyColumnsSelect(int columns) {
        StringBuilder jpql = new StringBuilder("select ");
        for (int i = 1; i <= columns; i++) {
            if (i > 1) {
                jpql.append(", ");
            }
            jpql.append("'x' as col").append(i);
        }
        jpql.append(" from Journal j");
        return jpql.toString();
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
