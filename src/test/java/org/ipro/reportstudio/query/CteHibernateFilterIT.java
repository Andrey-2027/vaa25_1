package org.ipro.reportstudio.query;

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

@DataJpaTest(properties = "spring.sql.init.mode=never")
@ContextConfiguration(classes = CteHibernateFilterIT.TestApplication.class)
class CteHibernateFilterIT {
    @SpringBootApplication
    static class TestApplication { }

    @jakarta.persistence.Entity(name = "FilteredCteRow")
    @Table(name = "filtered_cte_row")
    @FilterDef(name = "TENANT", parameters = @ParamDef(name = "allowedIds", type = Long.class))
    @Filter(name = "TENANT", condition = "tenant_id in (:allowedIds)")
    public static class FilteredCteRow {
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
    void reportExecutorAppliesHibernateFilterInsideCte() {
        persist(1L, "VISIBLE");
        persist(2L, "HIDDEN");
        entityManager().flush();
        entityManager().clear();

        var registry = new TestTenantRegistry();
        var cache = mock(RlsReadableIdsCache.class);
        when(cache.getReadableIds(anyString(), anyString())).thenReturn(List.of(1L));
        var activator = new RlsFilterActivator(registry, cache, () -> "smoke");
        var executor = new ReportQueryExecutor(entityManager(), activator);
        var fields = List.of(new QueryField("code", "code", String.class, "code", true, false, false));

        var result = executor.execute("with tmp1 as (select r.code as code from FilteredCteRow r) "
                + "select t.code as code from tmp1 t", Map.of(), fields, 50, 30_000);

        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.rows()[0].value(0)).isEqualTo("VISIBLE");
    }

    private void persist(Long tenantId, String code) {
        FilteredCteRow row = new FilteredCteRow();
        row.setTenantId(tenantId);
        row.setCode(code);
        entityManager().persist(row);
    }

    private static final class TestTenantRegistry extends RlsDimensionRegistry {
        TestTenantRegistry() { super("org.ipro.reportstudio.query"); }
        @Override public java.util.Set<String> dimensions() { return java.util.Set.of("TENANT"); }
        @Override public RlsDimensionKind kindOf(String dimension) { return RlsDimensionKind.FILTERABLE; }
    }

    private EntityManager entityManager() {
        return entityManager;
    }

    @org.springframework.beans.factory.annotation.Autowired EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Autowired EntityManagerFactory entityManagerFactory;
}
