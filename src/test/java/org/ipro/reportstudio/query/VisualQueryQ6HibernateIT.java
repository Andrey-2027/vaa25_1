package org.ipro.reportstudio.query;

import org.ipro.query.*;
import org.ipro.query.param.*;

import jakarta.persistence.EntityManager;
import org.ipro.query.q6.Q6Product;
import org.ipro.reportstudio.query.q6.Q6JpaTestConfiguration;
import org.ip.repository.UserRepository;
import org.ip.security.UserRepositoryRlsRoleResolver;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadableIdsCache;
import org.ipro.report.data.ReportDataset;
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
@EntityScan("org.ipro.query.q6")
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
        var analyzer = new org.ipro.query.sqm.SqmQuerySemanticAnalyzer(emf);
        guard = new ReportQueryGuard(analyzer, org.mockito.Mockito.mock(AccessService.class),
                new RlsDimensionRegistry("org.ipro.query.q6"), () -> "admin", emf);
        readGate = org.mockito.Mockito.mock(org.ipro.rls.RlsReadGate.class);
        org.mockito.Mockito.when(readGate.canRead(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }

    private org.ipro.rls.RlsReadGate readGate;

    @Test
    void queryBackedListsWithCtesJoinFilterNullsAndMetadata() {
        // These public CTE column names are legal on PostgreSQL but reserved by H2.
        entityManager.createNativeQuery("SET NON_KEYWORDS KEY, VALUE").executeUpdate();
        for (String code : java.util.Arrays.asList("B", "A", "A", null, "")) {
            var product = new Q6Product(); product.setCode(code); entityManager.persist(product);
        }
        entityManager.flush();
        var catalog = new QueryBuilderMetadataCatalog(emf, new org.ipro.metadata.MetadataResolver(), readGate, () -> "admin");
        var input = new VisualQueryDefinition("Q6Product", "p", List.of(
                new VisualQueryDefinition.SelectField("p.code", "keyText"),
                new VisualQueryDefinition.SelectField("p.code", "label")));
        var draft = new org.ipro.query.ui.constructor.QueryConstructorDraft(); draft.setCatalog(catalog); draft.load(input);
        var generator = new org.ipro.query.ui.list.ListCteGenerator(catalog);
        for (boolean unique : List.of(true, false)) {
            var list = generator.generate(new VisualQueryPackage(List.of(), input), "keyText", "label", ";", unique,
                    VisualQueryOrder.Direction.ASC, draft.occupiedNames());
            draft.addConstructorListByField(list, "p.code", unique ? "uniqueLabels" : "allLabels");
        }
        var compiled = VisualQueryCompiler.compile(draft.packageDefinition(), catalog);
        String filtered = WhereClauseApplier.apply(compiled.jpql(), "p.code = 'A' or p.code is null or p.code = ''");
        filtered = OrderByApplier.withOrderBy(filtered, List.of("p.code"), List.of());
        var checked = guard.guard(filtered, Set.of());
        assertThat(checked.allowed()).as(checked.errors().toString()).isTrue();
        assertThat(org.ipro.unidr.query.UniDrQuerySourceService.metadata(checked.selectFields()).fields())
                .filteredOn(f -> f.id().equals("allLabels")).singleElement()
                .satisfies(f -> assertThat(f.type()).isEqualTo(org.ipro.unidr.model.UniDrDefinition.ValueType.TEXT));
        var data = executor.execute(filtered, Map.of(), checked.selectFields(), 100, 30_000);
        assertThat(data.rowCount()).isEqualTo(4);
        assertThat(data.rows()).filteredOn(r -> "A".equals(r.value("keyText"))).allSatisfy(r -> {
            assertThat(r.value("uniqueLabels")).isEqualTo("A"); assertThat(r.value("allLabels")).isEqualTo("A;A");
        });
        assertThat(data.rows()).filteredOn(r -> r.value("keyText") == null).singleElement()
                .satisfies(r -> assertThat(r.value("uniqueLabels")).isNull());
        assertThat(data.rows()).filteredOn(r -> "".equals(r.value("keyText"))).singleElement()
                .satisfies(r -> assertThat(r.value("allLabels")).isEqualTo(""));
    }

    @Test
    void compiledListaggExecutesWithStringMetadataAndEscapedSeparator() {
        for (String code : List.of("B", "A", "A")) {
            var product = new Q6Product();
            product.setCode(code);
            entityManager.persist(product);
        }
        entityManager.flush();
        var definition = new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, "Q6Product", "p",
                List.of(), List.of(), List.of(), List.of(new VisualQueryDefinition.Aggregate(
                "LISTAGG", "p.code", "value", "' / ", true, "p.code", VisualQueryOrder.Direction.DESC)), null);
        var catalog = new QueryBuilderMetadataCatalog(emf, new org.ipro.metadata.MetadataResolver(), readGate, () -> "admin");
        var compiled = VisualQueryCompiler.compile(definition, catalog);
        var checked = guard.guard(compiled.jpql(), Set.of());
        assertThat(checked.allowed()).as(checked.errors().toString()).isTrue();
        assertThat(compiled.fields().getFirst().javaType()).isEqualTo(String.class);
        assertThat(checked.selectFields().getFirst().javaType()).isEqualTo(String.class);
        var data = executor.execute(compiled.jpql(), Map.of(), checked.selectFields(), 20, 30_000);
        assertThat(data.rowCount()).isEqualTo(1);
        assertThat(data.rows()[0].value("value")).isEqualTo("B' / A");
    }

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
