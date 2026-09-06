package org.ipro.reportstudio.query;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.reportstudio.query.q6.Q6JpaTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.sql.init.mode=never")
@ContextConfiguration(classes = Q6JpaTestConfiguration.class)
class CteVirtualCatalogIT {
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void compilesWhereAgainstPreviousCteFieldWithSharedParameter() {
        var first = new VisualQueryDefinition("Q6Product", "p", List.of(
                new VisualQueryDefinition.SelectField("code", "code")));
        var second = new VisualQueryDefinition(
                VisualQueryDefinition.CURRENT_VERSION, "tmp1", "t", List.of(
                new VisualQueryDefinition.SelectField("t.code", "code")),
                List.of(), List.of(), List.of(), List.of(), null,
                new org.ipro.filtergrid.filter.FilterConditionNode(new org.ipro.filtergrid.filter.FilterCondition(
                        "t.code", org.ipro.filtergrid.filter.FilterOperator.EQ, "A", null,
                        org.ipro.filtergrid.filter.FilterDataType.TEXT)),
                List.of(), List.of());
        var queryPackage = new VisualQueryPackage(List.of(
                new VisualQueryPackage.Cte("tmp1", first),
                new VisualQueryPackage.Cte("tmp2", new VisualQueryPackage.CteSource("t", "tmp1"), second)),
                new VisualQueryDefinition("Q6Product", "p", List.of(
                        new VisualQueryDefinition.SelectField("code", "code"))));
        var metadata = new QueryBuilderMetadataCatalog(entityManagerFactory,
                new org.ipro.metadata.MetadataResolver(),
                new org.ipro.rls.RlsReadGate(null, null) {
                    @Override public boolean canRead(Class<?> type, String user) { return true; }
                },
                () -> "smoke");

        var compiled = VisualQueryCompiler.compile(queryPackage, metadata);

        assertThat(compiled.jpql()).contains("from tmp1 t where t.code = :visualFilter_1");
        assertThat(compiled.bindings()).containsEntry("visualFilter_1", "A");
    }

    @Test
    void buildsTypedVirtualEntityThroughPublicApi() {
        var metadata = new QueryBuilderMetadataCatalog(entityManagerFactory,
                new org.ipro.metadata.MetadataResolver(),
                new org.ipro.rls.RlsReadGate(null, null) {
                    @Override public boolean canRead(Class<?> type, String user) { return true; }
                },
                () -> "smoke");
        var definition = new VisualQueryDefinition(
                VisualQueryDefinition.CURRENT_VERSION, "Q6Product", "p", List.of(
                new VisualQueryDefinition.SelectField("code", "code")),
                List.of(), List.of(), List.of(
                        new VisualQueryDefinition.Aggregate("COUNT_ROWS", "p.id", "rows"),
                        new VisualQueryDefinition.Aggregate("MIN", "p.code", "firstCode")),
                List.of(), null);

        var catalog = VisualQueryPackage.VirtualCatalog.empty()
                .add("tmp1", definition, metadata);
        var entity = catalog.entity("tmp1");

        assertThat(entity).isNotNull();
        assertThat(entity.fields()).extracting(QueryBuilderMetadataCatalog.Field::name)
                .containsExactly("code", "rows", "firstCode");
        assertThat(entity.fields().get(0).javaType()).isEqualTo(String.class);
        assertThat(entity.fields().get(1).javaType()).isEqualTo(Long.class);
        assertThat(entity.fields().get(2).javaType()).isEqualTo(String.class);
    }
}
