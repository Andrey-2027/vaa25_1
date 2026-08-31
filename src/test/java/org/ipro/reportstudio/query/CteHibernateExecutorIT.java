package org.ipro.reportstudio.query;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.query.q6.Q6JpaTestConfiguration;
import org.ipro.reportstudio.query.q6.Q6Product;
import org.ipro.rls.RlsFilterActivator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties = "spring.sql.init.mode=never")
@ContextConfiguration(classes = Q6JpaTestConfiguration.class)
class CteHibernateExecutorIT {
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void executesCompiledPackageThroughReportQueryExecutor() {
        var first = new VisualQueryDefinition("Q6Product", "p", List.of(
                new VisualQueryDefinition.SelectField("code", "code")));
        var second = new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION,
                "tmp1", "t", List.of(new VisualQueryDefinition.SelectField("t.code", "code")),
                List.of(), List.of(), List.of(), null);
        var main = new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION,
                "tmp2", "u", List.of(new VisualQueryDefinition.SelectField("u.code", "code")),
                List.of(), List.of(), List.of(), null);
        var pack = new VisualQueryPackage(List.of(
                new VisualQueryPackage.Cte("tmp1", first),
                new VisualQueryPackage.Cte("tmp2", new VisualQueryPackage.CteSource("t", "tmp1"), second)), main);

        entityManager.persist(product("A"));
        entityManager.persist(product("B"));
        entityManager.flush();

        var compiled = VisualQueryCompiler.compile(pack, null);
        var fields = List.of(new QueryField("code", "code", String.class, "code", true, false, false));
        var executor = new ReportQueryExecutor(entityManager, mock(RlsFilterActivator.class));
        var dataset = executor.execute(compiled.jpql(), compiled.bindings(), fields, 50, 30_000);

        assertThat(dataset.rowCount()).isEqualTo(2);
        assertThat(dataset.rows()).extracting(row -> row.value(0))
                .containsExactlyInAnyOrder("A", "B");
    }

    private Q6Product product(String code) {
        Q6Product product = new Q6Product();
        product.setCode(code);
        return product;
    }
}
