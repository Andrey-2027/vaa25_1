package org.ipro.reportstudio.query;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.ipro.reportstudio.query.q6.Q6JpaTestConfiguration;
import org.ipro.reportstudio.query.q6.Q6Product;
import org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Архитектурный smoke-тест CTE на фактической версии Hibernate проекта. */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.archive.autodetection=class",
        "spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false",
        "spring.sql.init.mode=never"
})
@ContextConfiguration(classes = Q6JpaTestConfiguration.class)
class CteHibernateSmokeIT {
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;

    private ReportQueryGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ReportQueryGuard(
                new SqmQuerySemanticAnalyzer(entityManagerFactory),
                org.mockito.Mockito.mock(org.ipro.rls.AccessService.class),
                new org.ipro.rls.RlsDimensionRegistry("org.ipro.reportstudio.query.q6"),
                () -> "smoke",
                entityManagerFactory);
    }

    @Test
    void hibernateExecutesCteAsFromRootAndGuardAcceptsIt() {
        String jpql = "with tmp1 as (select p.code as code from Q6Product p) "
                + "select t.code as code from tmp1 t";

        GuardResult checked = guard.guard(jpql, Set.of());
        assertThat(checked.allowed()).as(checked.errors().toString()).isTrue();
        assertThat(checked.selectFields()).extracting(org.ipro.reportstudio.data.QueryField::name)
                .containsExactly("code");

        var rows = entityManager.createQuery(jpql, Object[].class).getResultList();
        assertThat(rows).isNotNull();
    }

    @Test
    void hibernateAcceptsIndependentJoinToCte() {
        String jpql = "with tmp1 as (select p.code as code from Q6Product p) "
                + "select p.code as productCode, t.code as cteCode "
                + "from Q6Product p join tmp1 t on t.code = p.code";

        var query = entityManager.createQuery(jpql, Object[].class);
        query.setHint("org.hibernate.readOnly", true);
        assertThat(query.getResultList()).isNotNull();
    }

    @Test
    void hibernateExecutesChainedCteSources() {
        var first = new VisualQueryDefinition(
                "Q6Product", "p", java.util.List.of(
                new VisualQueryDefinition.SelectField("code", "code")));
        var second = new VisualQueryDefinition(
                VisualQueryDefinition.CURRENT_VERSION, "tmp1", "t", java.util.List.of(
                new VisualQueryDefinition.SelectField("t.code", "code")),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null);
        var main = new VisualQueryDefinition(
                VisualQueryDefinition.CURRENT_VERSION, "tmp2", "u", java.util.List.of(
                new VisualQueryDefinition.SelectField("u.code", "code")),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null);
        var queryPackage = new VisualQueryPackage(java.util.List.of(
                new VisualQueryPackage.Cte("tmp1", first),
                new VisualQueryPackage.Cte("tmp2", new VisualQueryPackage.CteSource("t", "tmp1"), second)), main);

        var compiled = VisualQueryCompiler.compile(queryPackage, null);
        assertThat(entityManager.createQuery(compiled.jpql(), Object[].class).getResultList()).isNotNull();
    }

    @Test
    void guardSeesOnlyRealEntityInsideCteAndDoesNotRejectCteName() {
        String jpql = "with tmp1 as (select p.code as code from Q6Product p) "
                + "select t.code as code from tmp1 t";

        GuardResult checked = guard.guard(jpql, Set.of());
        assertThat(checked.analysis().entities())
                .extracting(EntityUsage::entityName)
                .containsOnly(entityManagerFactory.getMetamodel().entity(Q6Product.class).getJavaType().getName());
        assertThat(checked.analysis().entities())
                .noneMatch(entity -> entity.entityName().equals("tmp1"));
    }
}
