package org.ipro.reportstudio.query;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadableIdsCache;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsDimensionKind;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RLS и guard для подзапросов: Hibernate @Filter применяется и внутри
 * подзапроса (уровень сессии), а SQM-анализатор guard'а видит сущности
 * подзапроса — проверка доступа их покрывает.
 */
@DataJpaTest(properties = "spring.sql.init.mode=never")
@ContextConfiguration(classes = SubqueryRlsIT.TestApplication.class)
class SubqueryRlsIT {
    @SpringBootApplication
    static class TestApplication { }

    /** Внешняя сущность запроса — без RLS-фильтра. */
    @Entity(name = "SubOuter")
    @Table(name = "subquery_rls_outer")
    public static class SubOuter {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private Long refId;
        private String code;
        public Long getId() { return id; }
        public Long getRefId() { return refId; }
        public void setRefId(Long value) { refId = value; }
        public String getCode() { return code; }
        public void setCode(String value) { code = value; }
    }

    /** Внутренняя сущность подзапроса — с TENANT-фильтром. */
    @Entity(name = "SubInner")
    @Table(name = "subquery_rls_inner")
    @FilterDef(name = "TENANT", parameters = @ParamDef(name = "allowedIds", type = Long.class))
    @Filter(name = "TENANT", condition = "tenant_id in (:allowedIds)")
    public static class SubInner {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private Long tenantId;
        private String code;
        public Long getId() { return id; }
        public Long getTenantId() { return tenantId; }
        public void setTenantId(Long value) { tenantId = value; }
        public String getCode() { return code; }
        public void setCode(String value) { code = value; }
    }

    @Test
    void reportExecutorAppliesHibernateFilterInsideSubquery() {
        persistInner(1L, "INNER-1");
        persistInner(2L, "INNER-2");
        persistOuter(1L, "OUT-1");
        persistOuter(2L, "OUT-2");
        entityManager.flush();
        entityManager.clear();

        var registry = new TestTenantRegistry();
        var cache = mock(RlsReadableIdsCache.class);
        when(cache.getReadableIds(anyString(), anyString())).thenReturn(List.of(1L));
        var activator = new RlsFilterActivator(registry, cache, () -> "smoke");
        var executor = new ReportQueryExecutor(entityManager, activator);
        var fields = List.of(new QueryField("code", "code", String.class, "code", true, false, false));

        // Внешняя таблица без фильтра, подзапрос — по фильтрованной сущности.
        var result = executor.execute(
                "select r.code as code from SubOuter r where r.refId in (select f.id from SubInner f)",
                Map.of(), fields, 50, 30_000);

        // Фильтр внутри подзапроса скрыл строку tenant 2: осталась только OUT-1.
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.rows()[0].value(0)).isEqualTo("OUT-1");
    }

    @Test
    void semanticAnalyzerCollectsEntitiesInsideSubquery() {
        var analyzer = new org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer(entityManagerFactory);
        var analysis = analyzer.analyze(
                "select r.code as code from SubOuter r where r.refId in (select f.id from SubInner f)");
        // Тестовые сущности — вложенные классы: Hibernate возвращает FQN, а не simple name.
        assertThat(analysis.entities()).extracting(EntityUsage::entityName)
                .anySatisfy(name -> assertThat(name).endsWith("SubOuter"))
                .anySatisfy(name -> assertThat(name).endsWith("SubInner"));
        assertThat(analysis.entities()).extracting(EntityUsage::inSubquery)
                .contains(true);
    }

    private void persistInner(Long tenantId, String code) {
        SubInner row = new SubInner();
        row.setTenantId(tenantId);
        row.setCode(code);
        entityManager.persist(row);
    }

    private void persistOuter(Long refId, String code) {
        SubOuter row = new SubOuter();
        row.setRefId(refId);
        row.setCode(code);
        entityManager.persist(row);
    }

    private static final class TestTenantRegistry extends RlsDimensionRegistry {
        TestTenantRegistry() { super("org.ipro.reportstudio.query"); }
        @Override public java.util.Set<String> dimensions() { return java.util.Set.of("TENANT"); }
        @Override public RlsDimensionKind kindOf(String dimension) { return RlsDimensionKind.FILTERABLE; }
    }

    @org.springframework.beans.factory.annotation.Autowired EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Autowired EntityManagerFactory entityManagerFactory;
}
