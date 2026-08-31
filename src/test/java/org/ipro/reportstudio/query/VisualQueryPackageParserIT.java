package org.ipro.reportstudio.query;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.reportstudio.query.q6.Q6JpaTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.sql.init.mode=never")
@ContextConfiguration(classes = Q6JpaTestConfiguration.class)
class VisualQueryPackageParserIT {
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void parsesWithChainIntoPackage() {
        var catalog = new QueryBuilderMetadataCatalog(entityManagerFactory,
                new org.ipro.metadata.MetadataResolver(),
                new org.ipro.rls.RlsReadGate(null, null) {
                    @Override public boolean canRead(Class<?> type, String user) { return true; }
                },
                () -> "smoke");
        String entity = entityManagerFactory.getMetamodel().entity(
                org.ipro.reportstudio.query.q6.Q6Product.class).getName();
        String jpql = "with tmp1 as (select p.code as code from " + entity + " p), "
                + "tmp2 as (select t.code as code from tmp1 t) "
                + "select u.code as code from tmp2 u";

        var parsed = new VisualQueryTextParser(catalog).parsePackage(jpql);

        assertThat(parsed.queryPackage()).isNotNull();
        assertThat(parsed.queryPackage().ctes()).extracting(VisualQueryPackage.Cte::name)
                .containsExactly("tmp1", "tmp2");
        assertThat(parsed.queryPackage().ctes().get(1).source())
                .isEqualTo(new VisualQueryPackage.CteSource("t", "tmp1"));
        assertThat(parsed.queryPackage().main().entityName()).isEqualTo("tmp2");

        var compiled = VisualQueryCompiler.compile(parsed.queryPackage(), catalog);
        assertThat(compiled.jpql()).contains("with tmp1 as (")
                .contains("tmp2 as (select t.code as code from tmp1 t)")
                .endsWith("select u.code as code from tmp2 u");
    }
}
