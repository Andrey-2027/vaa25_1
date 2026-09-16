package org.ipro.reportstudio.query;

import jakarta.persistence.EntityManager;
import org.ipro.reportstudio.query.q6.Q6JpaTestConfiguration;
import org.ipro.reportstudio.query.q6.Q6Product;
import org.ip.repository.UserRepository;
import org.ip.security.UserRepositoryRlsRoleResolver;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadableIdsCache;
import org.ipro.reportstudio.data.ReportDataset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.ipro.rls.config.RlsPersistenceAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.archive.autodetection=class",
        "spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false",
        // data.sql основного приложения ссылается на таблицы org.ip (role и др.),
        // которых нет в q6-срезе — seed-данные этому тесту не нужны.
        "spring.sql.init.mode=never"
})
@ImportAutoConfiguration(RlsPersistenceAutoConfiguration.class)
// @EntityScan модуля вытесняет autopackage среза (q6): без явного пакета Q6Product
// выпадает из persistence unit (Not an entity). Пакет rls даёт RlsPersistenceAutoConfiguration.
@EntityScan("org.ipro.reportstudio.query.q6")
@ContextConfiguration(classes = Q6JpaTestConfiguration.class)
class VisualQueryQ6HibernateIT {
    @Autowired EntityManager entityManager;
    @Autowired jakarta.persistence.EntityManagerFactory emf;


    private ReportQueryExecutor executor;
    private ReportQueryGuard guard;

    @BeforeEach
    void setUp() {
        var activator = org.mockito.Mockito.mock(RlsFilterActivator.class);
        executor = new ReportQueryExecutor(entityManager, activator);
        var analyzer = new org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer(emf);
        guard = new ReportQueryGuard(analyzer, org.mockito.Mockito.mock(AccessService.class),
                new RlsDimensionRegistry("org.ipro.reportstudio.query.q6"), () -> "admin", emf);
        readGate = org.mockito.Mockito.mock(org.ipro.rls.RlsReadGate.class);
        org.mockito.Mockito.when(readGate.canRead(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }

    private org.ipro.rls.RlsReadGate readGate;

    @Test
    void generatedVisualQueryPassesGuardAndExecutesWithParameterAndOrder() {
        String entity = emf.getMetamodel().entity(Q6Product.class).getName();
        var definition = new VisualQueryDefinition(3, entity, "s",
                List.of(new VisualQueryDefinition.SelectField("code", "code")),
                List.of(), List.of(), List.of(), List.of(), null,
                new org.ipro.filtergrid.filter.FilterConditionNode(new org.ipro.filtergrid.filter.FilterCondition(
                        "s.code", org.ipro.filtergrid.filter.FilterOperator.EQ, "S-1", null,
                        org.ipro.filtergrid.filter.FilterDataType.TEXT)),
                List.of(), List.of(new VisualQueryOrder("code", VisualQueryOrder.Direction.ASC)));

        var catalog = new QueryBuilderMetadataCatalog(emf,
                new org.ipro.metadata.MetadataResolver(),
                readGate, () -> "admin");
        var compiled = VisualQueryCompiler.compile(definition, catalog);
        GuardResult checked = guard.guard(compiled.jpql(), Set.of("visualFilter_1"));
        assertThat(checked.allowed()).as(checked.errors().toString()).isTrue();

        ReportDataset dataset = executor.execute(compiled.jpql(),
                Map.of("visualFilter_1", "SPEC-B"), checked.selectFields(), 20, 30_000);
        assertThat(dataset.rowCount()).isGreaterThanOrEqualTo(0);
    }
}
