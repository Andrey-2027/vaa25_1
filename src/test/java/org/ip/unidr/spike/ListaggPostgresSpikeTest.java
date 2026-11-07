package org.ipro.unidr.spike;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.ip.Application;
import org.ip.model.AccountingObjectInstallation;
import org.ip.model.AccountingObjectInstallationItem;
import org.ip.model.AccountingType;
import org.ip.model.Branch;
import org.ip.model.Nomenclature;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.Workshop;
import org.ipro.report.data.QueryField;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QuerySource;
import org.ipro.query.param.EntityParamRefresher;
import org.ipro.query.ReportContext;
import org.ipro.query.param.ReportParamResolver;
import org.ipro.query.OrderByApplier;
import org.ipro.query.ReportQueryAssemblyService;
import org.ipro.query.ReportQueryExecutor;
import org.ipro.query.ReportQueryGuard;
import org.ipro.query.ReportReadAuthorizer;
import org.ipro.query.WhereClauseApplier;
import org.ipro.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadableIdsCache;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.rls.c5.DefaultC5PermissionEvaluator;
import org.ipro.rls.config.RlsPersistenceAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "listagg.postgres", matches = "true")
@DataJpaTest(showSql = false, properties = {
    "spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.ipro.unidr.spike.ListaggPostgresSpikeTest$SqlCapture"
})
@ImportAutoConfiguration(RlsPersistenceAutoConfiguration.class)
@ContextConfiguration(classes = Application.class)
class ListaggPostgresSpikeTest {
    private static final String HQL = """
        with list1 as (
          select d.id as docId,
                 listagg(distinct n.name, ', ') within group (order by n.name) as values
          from AccountingObjectInstallationItem i
          join AccountingObjectInstallation d on i.document.id = d.id
          join Nomenclature n on i.nomenclature.id = n.id
          group by d.id
        )
        select d.id as id, d.number as number, l1.values as values
        from AccountingObjectInstallation d
        left join list1 l1 on l1.docId = d.id
        order by d.number
        """;

    private static final String TWO_LISTS = """
        with list1 as (
          select d.id as docId,
                 listagg(distinct n.name, ', ') within group (order by n.name) as values
          from AccountingObjectInstallationItem i
          join AccountingObjectInstallation d on i.document.id = d.id
          join Nomenclature n on i.nomenclature.id = n.id
          group by d.id
        ), list2 as (
          select d.id as docId,
                 listagg(n.code, '; ') within group (order by n.code) as values
          from AccountingObjectInstallationItem i
          join AccountingObjectInstallation d on i.document.id = d.id
          join Nomenclature n on i.nomenclature.id = n.id
          group by d.id
        )
        select d.number as number, l1.values as names, l2.values as codes
        from AccountingObjectInstallation d
        left join list1 l1 on l1.docId = d.id
        left join list2 l2 on l2.docId = d.id
        order by d.number
        """;

    private static UniDrPostgresSandbox postgres;

    private static final String WORKSHOP_LIST = """
        with list1 as (
          select p.id as docId,
                 listagg(distinct w.name, ', ') within group (order by w.name) as values
          from AccountingObjectInstallationItem i
          join AccountingObjectInstallation p on i.document.id = p.id
          join Workshop w on p.workshop.id = w.id
          group by p.id
        )
        select d.number as number, l1.values as values
        from AccountingObjectInstallation d
        left join list1 l1 on l1.docId = d.id
        order by d.number
        """;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        if (!Boolean.getBoolean("listagg.postgres")) return;
        postgres = UniDrPostgresSandbox.create();
        properties.add("spring.test.database.replace", () -> "NONE");
        properties.add("spring.datasource.url", postgres::url);
        properties.add("spring.datasource.username", postgres::user);
        properties.add("spring.datasource.password", postgres::password);
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.jpa.properties.hibernate.default_schema", postgres::schema);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @AfterAll
    static void dropSandbox() throws Exception {
        if (postgres != null) postgres.close();
    }

    public static final class SqlCapture implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static final ThreadLocal<List<String>> SQL = ThreadLocal.withInitial(ArrayList::new);

        @Override
        public String inspect(String sql) {
            SQL.get().add(sql);
            return sql;
        }
    }

    @Autowired EntityManager em;
    @Autowired EntityManagerFactory factory;
    @Autowired AccessGrantRepository grants;

    private final String subject = "listagg-spike-user";
    private ReportQueryGuard guard;
    private ReportQueryExecutor executor;
    private ReportQueryAssemblyService assembly;
    private Workshop workshop;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(subject, "", List.of()));
        var registry = new RlsDimensionRegistry("org.ip");
        registry.rebuild();
        RlsCurrentUser current = () -> subject;
        var access = new AccessService(grants, user -> List.of(), registry);
        var permissions = new DefaultC5PermissionEvaluator(grants, user -> List.of());
        var activator = new RlsFilterActivator(registry, new RlsReadableIdsCache(access), current);
        var readGate = new RlsReadGate(permissions, registry);
        var authorizer = new ReportReadAuthorizer(factory, permissions, current, List.of(), access, registry);
        guard = new ReportQueryGuard(new SqmQuerySemanticAnalyzer(factory), access, registry, current, factory, authorizer);
        executor = new ReportQueryExecutor(em, activator, current, guard, authorizer);
        var refresher = new EntityParamRefresher(em, activator, readGate, current,
            factory.unwrap(SessionFactoryImplementor.class));
        var resolver = new ReportParamResolver(refresher, access, current,
            factory.unwrap(SessionFactoryImplementor.class), new ObjectMapper(), "BRANCH");
        assembly = new ReportQueryAssemblyService(guard, resolver, refresher);
        fixtures();
        em.flush();
        em.clear();
        SqlCapture.SQL.get().clear();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        SqlCapture.SQL.remove();
    }

    @Test
    void pilotListaggRunsOnceThroughGuardTypingAndExecutor() throws Exception {
        var description = assembly.describe(definition(HQL));

        assertThat(description.fields()).extracting(QueryField::name)
            .containsExactly("id", "number", "values");
        assertThat(description.fields()).filteredOn(field -> field.name().equals("values"))
            .singleElement().satisfies(field -> assertThat(field.javaType()).isEqualTo(String.class));

        var dataset = executor.execute(description, 100, 30_000);

        assertThat(dataset.rowCount()).isEqualTo(3);
        assertThat(dataset.rows()).extracting(row -> row.value("number"))
            .containsExactly("LST-01", "LST-02", "LST-03");
        assertThat(dataset.rows()).extracting(row -> row.value("values"))
            .containsExactly("Деталь X, Деталь Y", "Деталь Y", null);

        String sql = dataSql();
        assertThat(sql.toLowerCase(Locale.ROOT)).contains("string_agg", "distinct", "order by");
        writeEvidence("pilot", description, dataset, sql);
    }

    @Test
    void generatedConstructorListExecutesAndRestoresIntention() {
        var readGate = org.mockito.Mockito.mock(RlsReadGate.class);
        org.mockito.Mockito.when(readGate.canRead(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        var catalog = catalog(readGate);
        var generator = new org.ipro.query.ui.list.ListCteGenerator(catalog);
        var source = generator.generate("AccountingObjectInstallationItem", "nomenclature.name", ", ", true,
                org.ipro.query.VisualQueryOrder.Direction.ASC, java.util.Set.of());
        var draft = new org.ipro.query.ui.constructor.QueryConstructorDraft();
        draft.setCatalog(catalog);
        draft.load(new org.ipro.query.VisualQueryDefinition("AccountingObjectInstallation", "d", List.of(
                new org.ipro.query.VisualQueryDefinition.SelectField("d.number", "number"))));
        draft.addConstructorList(source, "d", "names");
        var pack = draft.packageDefinition();
        String json = org.ipro.query.ui.list.ListConstructorSources.attach(
                new org.ipro.query.VisualQueryPackageJsonCodec().write(pack), draft.lists());
        assertThat(org.ipro.query.ui.list.ListConstructorSources.read(json, catalog)).containsExactly(source);
        var compiled = org.ipro.query.VisualQueryCompiler.compile(pack, catalog);
        var checked = guard.guard(compiled.jpql(), java.util.Set.of());
        assertThat(checked.allowed()).as(checked.errors().toString()).isTrue();
        assertThat(compiled.fields()).filteredOn(f -> f.name().equals("names")).singleElement()
                .satisfies(f -> assertThat(f.javaType()).isEqualTo(String.class));
        var data = executor.execute(compiled.jpql(), Map.of(), checked.selectFields(), 100, 30_000);
        assertThat(data.rowCount()).isEqualTo(3);
        assertThat(java.util.Arrays.stream(data.rows()).filter(r -> r.value("number").equals("LST-01")).findFirst().orElseThrow().value("names"))
                .isEqualTo("Деталь X, Деталь Y");
    }

    @Test
    void tableSectionQuerySourceResolvesJoinedTextAndExecutes() {
        var readGate = org.mockito.Mockito.mock(RlsReadGate.class);
        org.mockito.Mockito.when(readGate.canRead(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        var catalog = catalog(readGate);
        var generator = new org.ipro.query.ui.list.ListCteGenerator(catalog);
        var pilot = generator.generate("AccountingObjectInstallationItem", "nomenclature.name", ", ", true,
                org.ipro.query.VisualQueryOrder.Direction.ASC, java.util.Set.of());
        var generated = pilot.stages().getFirst().definition();
        var query = new org.ipro.query.VisualQueryDefinition(generated.version(), generated.entityName(), generated.entityAlias(),
                List.of(new org.ipro.query.VisualQueryDefinition.SelectField("p.id", "ownerId"),
                        new org.ipro.query.VisualQueryDefinition.SelectField("listValue1.name", "label")),
                generated.joins(), List.of(), List.of(), null);
        var source = generator.generate(new org.ipro.query.VisualQueryPackage(List.of(), query),
                "ownerId", "label", ", ", true, null, java.util.Set.of());
        var draft = new org.ipro.query.ui.constructor.QueryConstructorDraft();
        draft.setCatalog(catalog);
        draft.load(new org.ipro.query.VisualQueryDefinition("AccountingObjectInstallation", "d", List.of(
                new org.ipro.query.VisualQueryDefinition.SelectField("d.number", "number"))));
        draft.addConstructorListByField(source, "d.id", "names");
        var compiled = org.ipro.query.VisualQueryCompiler.compile(draft.packageDefinition(), catalog);
        var checked = guard.guard(compiled.jpql(), java.util.Set.of());
        assertThat(checked.allowed()).as(checked.errors().toString()).isTrue();
        var data = executor.execute(compiled.jpql(), Map.of(), checked.selectFields(), 100, 30_000);
        assertThat(data.rowCount()).isEqualTo(3);
        assertThat(java.util.Arrays.stream(data.rows()).filter(r -> r.value("number").equals("LST-01")).findFirst().orElseThrow().value("names"))
                .isEqualTo("Деталь X, Деталь Y");
    }

    @Test
    void arbitrarySourceWithStringKeyExecutesAsList() throws Exception {
        var readGate = org.mockito.Mockito.mock(RlsReadGate.class);
        org.mockito.Mockito.when(readGate.canRead(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        var catalog = catalog(readGate);
        var query = new org.ipro.query.VisualQueryDefinition("Nomenclature", "n", List.of(
                new org.ipro.query.VisualQueryDefinition.SelectField("n.code", "code"),
                new org.ipro.query.VisualQueryDefinition.SelectField("n.name", "name")));
        var generator = new org.ipro.query.ui.list.ListCteGenerator(catalog);
        var source = generator.generate(new org.ipro.query.VisualQueryPackage(List.of(), query),
                "code", "name", ", ", true, null, java.util.Set.of());
        var draft = new org.ipro.query.ui.constructor.QueryConstructorDraft();
        draft.setCatalog(catalog); draft.load(query);
        draft.addConstructorListByField(source, "n.code", "names");
        var compiled = org.ipro.query.VisualQueryCompiler.compile(draft.packageDefinition(), catalog);
        var checked = guard.guard(compiled.jpql(), java.util.Set.of());
        assertThat(checked.allowed()).as(checked.errors().toString()).isTrue();
        var data = executor.execute(compiled.jpql(), Map.of(), checked.selectFields(), 100, 30_000);
        assertThat(data.rowCount()).isEqualTo(2);
        assertThat(data.rows()).allSatisfy(row -> assertThat(row.value("names")).isEqualTo(row.value("name")));
        writeEvidence("generated-query-source", compiled, data, dataSql());
        Files.writeString(Path.of("outputs/listagg-spike/explain-generated-query-source.txt"), explain(dataSql()) + "\n");
    }

    @Test
    @EnabledIfSystemProperty(named = "listagg.load", matches = "true")
    void acceptanceTenThousandKeysAndHundredThousandRows() throws Exception {
        // Tables are owned exclusively by this disposable sandbox schema.
        em.createNativeQuery("create table listagg_load_owner(id bigint primary key)").executeUpdate();
        em.createNativeQuery("create table listagg_load_row(owner_id bigint not null, label text)").executeUpdate();
        em.createNativeQuery("insert into listagg_load_owner select generate_series(1,10000)").executeUpdate();
        em.createNativeQuery("insert into listagg_load_row select ((g-1)/10)+1, 'Value ' || ((g-1)%7) from generate_series(1,100000) g").executeUpdate();
        em.createNativeQuery("create index on listagg_load_row(owner_id)").executeUpdate();
        em.createNativeQuery("analyze listagg_load_owner").executeUpdate();
        em.createNativeQuery("analyze listagg_load_row").executeUpdate();
        String one = "with list1 as (select owner_id as key, string_agg(distinct label, ', ' order by label) as value from listagg_load_row group by owner_id) ";
        String two = one.substring(0, one.length()-1) + ", list2 as (select owner_id as key, string_agg(label, '; ' order by label desc) as value from listagg_load_row group by owner_id) ";
        var summary = new StringBuilder("keys=10000; sourceRows=100000; runs=3; local synthetic sandbox\n");
        Files.createDirectories(Path.of("outputs/listagg-spike"));
        for (boolean pair : List.of(false, true)) for (String scope : List.of("full", "narrow", "empty")) {
            String sql = (pair ? two : one) + "select d.id,l1.value" + (pair ? ",l2.value" : "")
                    + " from listagg_load_owner d left join list1 l1 on l1.key=d.id"
                    + (pair ? " left join list2 l2 on l2.key=d.id" : "")
                    + (scope.equals("narrow") ? " where d.id between 1 and 10" : scope.equals("empty") ? " where d.id=-1" : "");
            String name = "load-" + (pair ? "two-" : "one-") + scope;
            var times = new ArrayList<Double>();
            for (int run = 0; run < 3; run++) {
                long start = System.nanoTime();
                List<?> rows = em.createNativeQuery(sql).getResultList();
                times.add((System.nanoTime() - start) / 1_000_000.0);
                assertThat(rows).hasSize(scope.equals("full") ? 10000 : scope.equals("narrow") ? 10 : 0);
                if (!rows.isEmpty()) assertThat(((Object[]) rows.getFirst())[1]).isEqualTo("Value 0, Value 1, Value 2, Value 3, Value 4, Value 5, Value 6");
            }
            List<?> plan = em.createNativeQuery("explain (analyze,buffers,format text) " + sql).getResultList();
            Files.writeString(Path.of("outputs/listagg-spike/" + name + ".txt"), sql + "\n\n" + String.join("\n", plan.stream().map(String::valueOf).toList()));
            summary.append(name).append(" clientMs=").append(times).append('\n');
        }
        Files.writeString(Path.of("outputs/listagg-spike/load-summary.txt"), summary);
    }

    @Test
    void whereClauseAboveCteKeepsTheListBodyIntact() throws Exception {
        String filtered = WhereClauseApplier.apply(HQL, "d.number = 'LST-01'");
        var checked = guard.guard(filtered, java.util.Set.of());
        assertThat(checked.allowed()).as(checked.errors().toString()).isTrue();

        var dataset = executor.execute(filtered, Map.of(), checked.selectFields(), 100, 30_000);

        assertThat(dataset.rowCount()).isEqualTo(1);
        assertThat(dataset.rows()[0].value("values")).isEqualTo("Деталь X, Деталь Y");
        assertThat(filtered.toLowerCase(Locale.ROOT))
            .contains("listagg(distinct n.name, ', ') within group (order by n.name)");
        writeEvidence("filtered", filtered, dataset, dataSql());
    }

    @Test
    void orderByApplierDoesNotRewriteWithinGroup() {
        String noMainOrder = HQL.substring(0, HQL.toLowerCase(Locale.ROOT).lastIndexOf("order by d.number"));
        String ordered = OrderByApplier.withOrderBy(noMainOrder, List.of("d.number"), List.of());
        assertThat(ordered.toLowerCase(Locale.ROOT))
            .contains("within group (order by n.name)")
            .endsWith("order by d.number");
    }

    @Test
    void twoIndependentListsDoNotMultiplyMainRows() throws Exception {
        var description = assembly.describe(definition(TWO_LISTS));
        var dataset = executor.execute(description, 100, 30_000);

        assertThat(dataset.rowCount()).isEqualTo(3);
        assertThat(dataset.rows()).extracting(row -> row.value("number"))
            .containsExactly("LST-01", "LST-02", "LST-03");
        assertThat(dataset.rows()[0].value("names")).isEqualTo("Деталь X, Деталь Y");
        assertThat(dataset.rows()[0].value("codes")).isEqualTo("N-X; N-X; N-Y");
        assertThat(dataset.rows()[2].value("names")).isNull();
        assertThat(dataset.rows()[2].value("codes")).isNull();
        writeEvidence("two-lists", description, dataset, dataSql());
    }

    @Test
    void explainAnalyzeShowsOneAggregatePerDocument() throws Exception {
        var description = assembly.describe(definition(HQL));
        var dataset = executor.execute(description, 100, 30_000);
        assertThat(dataset.rowCount()).isEqualTo(3);
        String sql = dataSql();
        String text = explain(sql);
        Files.createDirectories(Path.of("outputs/listagg-spike"));
        Files.writeString(Path.of("outputs/listagg-spike/explain-analyze.txt"), text + "\n");
        assertThat(text).containsIgnoringCase("Aggregate");
        for (var scenario : Map.of("filtered", WhereClauseApplier.apply(HQL, "d.number = 'LST-01'"),
            "two-lists", TWO_LISTS, "empty", WhereClauseApplier.apply(HQL, "d.number = 'missing'" )).entrySet()) {
            var query = assembly.describe(definition(scenario.getValue()));
            executor.execute(query, 100, 30_000);
            Files.writeString(Path.of("outputs/listagg-spike/explain-" + scenario.getKey() + ".txt"),
                explain(dataSql()) + "\n");
        }
    }

    private String explain(String sql) {
        // The executor's only JDBC placeholder here is setMaxResults(100).
        assertThat(sql.chars().filter(c -> c == '?').count()).isEqualTo(1);
        var query = em.createNativeQuery("explain (analyze, buffers, format text) " + sql);
        query.setParameter(1, 100);
        List<?> plan = query.getResultList();
        return String.join("\n", plan.stream().map(String::valueOf).toList());
    }

    @Test
    void uniDrTextMetadataPrintAndSavedQueryLifecycle() throws Exception {
        var service = new org.ipro.unidr.query.UniDrQuerySourceService(assembly, factory);
        var source = org.ipro.unidr.query.UniDrQueryDefinitions.source(
            definition(HQL), null);
        var schema = service.describe(source);
        assertThat(schema.fields()).filteredOn(field -> field.id().equals("values"))
            .singleElement().satisfies(field -> assertThat(field.type())
                .isEqualTo(org.ipro.unidr.model.UniDrDefinition.ValueType.TEXT));
        var definition = new org.ipro.unidr.model.UniDrDefinition(1, "query", 1, "LISTAGG spike",
            List.of(new org.ipro.unidr.model.UniDrDefinition.Column("number", null),
                new org.ipro.unidr.model.UniDrDefinition.Column("values", null)),
            List.of(), List.of(), List.of(), List.of(), List.of(),
            org.ipro.unidr.model.UniDrDefinition.Mode.DETAILS, true).withQuery(source);
        var catalog = new org.ipro.unidr.source.UniDrSourceCatalog(List.of(), service::describe);
        var validator = new org.ipro.unidr.model.UniDrDefinitionValidator(catalog);
        var codec = new org.ipro.unidr.model.UniDrDefinitionJsonCodec(validator);
        var saved = codec.decode(codec.encode(definition));
        assertThat(saved).isEqualTo(definition);
        var description = service.assemble(saved.query(), ReportContext.empty(subject), Map.of());
        var data = service.prepare(saved, description, executor.execute(description, 100, 30_000), Map.of());
        assertThat(data.rows()[0].value("values")).isEqualTo("Деталь X, Деталь Y");
        // Rendering consumes detached data after the read transaction, on its own thread.
        var renderCatalog = new org.ipro.unidr.source.UniDrSourceCatalog(List.of(), ignored -> schema);
        var print = java.util.concurrent.CompletableFuture.supplyAsync(() ->
            new org.ipro.unidr.render.UniDrReportCompiler(renderCatalog,
                new org.ipro.unidr.model.UniDrDefinitionValidator(renderCatalog)).compile(saved, data)).join();
        String printed = net.sf.jasperreports.engine.JasperExportManager.exportReportToXml(print);
        assertThat(printed).contains("Деталь X, Деталь Y");
        Files.createDirectories(Path.of("outputs/listagg-spike"));
        Files.writeString(Path.of("outputs/listagg-spike/unidr-print.xml"), printed);
        var edited = saved.withQuery(org.ipro.unidr.query.UniDrQueryDefinitions.source(
            definition(HQL.replace("order by n.name)", "order by n.name desc)")), null));
        var reopened = codec.decode(codec.encode(edited));
        var editedQuery = service.assemble(reopened.query(), ReportContext.empty(subject), Map.of());
        assertThat(executor.execute(editedQuery, 100, 30_000).rows()[0].value("values"))
            .isEqualTo("Деталь Y, Деталь X");
    }

    @Test
    void parentAndSectionEntityReadAreRequired() {
        for (Class<?> entity : List.of(AccountingObjectInstallation.class, AccountingObjectInstallationItem.class)) {
            em.createQuery("delete from AccessGrant g where g.subjectKey = :user and g.dimension = :key")
                .setParameter("user", subject).setParameter("key", C5PermissionKeys.entityRead(entity))
                .executeUpdate();
            SqlCapture.SQL.get().clear();
            assertThatThrownBy(() -> assembly.describe(definition(HQL)))
                .hasMessageContaining("ENTITY-read").hasMessageContaining(entity.getName());
            assertThat(SqlCapture.SQL.get()).noneMatch(sql -> sql.contains("string_agg"));
            grant(C5PermissionKeys.entityRead(entity), null);
            em.flush();
        }
    }

    @Test
    void missingInnerParentIdentityJoinIsRejected() {
        String unsafe = HQL.replace("join AccountingObjectInstallation d on i.document.id = d.id",
            "left join AccountingObjectInstallation d on i.document.id = d.id");
        var checked = guard.guard(unsafe, java.util.Set.of());
        assertThat(checked.allowed()).isFalse();
        assertThat(checked.errors()).anyMatch(error -> error.contains("RLS_SECTION_PARENT_JOIN_REQUIRED"));
    }

    @Test
    void pilotModelDoesNotApplyBranchRowFiltering() {
        var type = em.createQuery("from AccountingType", AccountingType.class).getSingleResult();
        var item = em.createQuery("from Nomenclature where code = 'N-X'", Nomenclature.class).getSingleResult();
        workshop = workshop("LST-HW", "Закрытый цех", branch("LST-HB"));
        positions(document("LST-HIDDEN", 4), type, item);
        em.flush();
        em.clear();
        var description = assembly.describe(definition(HQL));
        var data = executor.execute(description, 100, 30_000);
        assertThat(data.rows()).extracting(row -> row.value("number"))
            .containsExactly("LST-01", "LST-02", "LST-03", "LST-HIDDEN");
        // This pilot has no FILTERABLE branch dimension; record the limitation explicitly.
        assertThat(dataSql()).doesNotContain("workshop_id in");
    }

    @Test
    void workshopBranchFilterInsideCtePreservesOwnerWithNullList() throws Exception {
        var type = em.createQuery("from AccountingType", AccountingType.class).getSingleResult();
        var item = em.createQuery("from Nomenclature where code = 'N-X'", Nomenclature.class).getSingleResult();
        workshop = workshop("LST-HW", "Закрытый цех", branch("LST-HB"));
        positions(document("LST-HIDDEN", 4), type, item);
        em.flush();
        em.clear();
        var description = assembly.describe(definition(WORKSHOP_LIST));
        var data = executor.execute(description, 100, 30_000);
        assertThat(data.rows()).extracting(row -> row.value("number"))
            .containsExactly("LST-01", "LST-02", "LST-03", "LST-HIDDEN");
        assertThat(data.rows()).extracting(row -> row.value("values"))
            .containsExactly("Цех списка", "Цех списка", null, null);
        assertThat(dataSql()).contains("branch_id in");
        writeEvidence("workshop-rls", description, data, dataSql());
    }

    @Test
    void workshopEntityReadIsRequiredBeforeAggregateSql() {
        em.createQuery("delete from AccessGrant g where g.subjectKey = :user and g.dimension = :key")
            .setParameter("user", subject).setParameter("key", C5PermissionKeys.entityRead(Workshop.class))
            .executeUpdate();
        SqlCapture.SQL.get().clear();
        assertThatThrownBy(() -> assembly.describe(definition(WORKSHOP_LIST)))
            .hasMessageContaining("ENTITY-read").hasMessageContaining(Workshop.class.getName());
        assertThat(SqlCapture.SQL.get()).noneMatch(sql -> sql.contains("string_agg"));
    }

    @Test
    void noReadableBranchRejectsWholeQueryInsteadOfReturningNullList() {
        em.createQuery("delete from AccessGrant g where g.subjectKey = :user and g.dimension = 'BRANCH'")
            .setParameter("user", subject).executeUpdate();
        SqlCapture.SQL.get().clear();
        assertThatThrownBy(() -> assembly.describe(definition(WORKSHOP_LIST)))
            .hasMessageContaining("Нет доступа на чтение по измерению «BRANCH»");
        assertThat(SqlCapture.SQL.get()).noneMatch(sql -> sql.contains("string_agg"));
    }

    @Test
    void workshopAssociationJoinCannotReplaceIdentityJoin() {
        String unsafe = WORKSHOP_LIST.replace("join Workshop w on p.workshop.id = w.id", "join p.workshop w");
        var checked = guard.guard(unsafe, java.util.Set.of());
        assertThat(checked.allowed()).isFalse();
        assertThat(checked.errors()).anyMatch(error -> error.contains("RLS_ASSOCIATION_JOIN_REQUIRED"));
    }

    @Test
    void missingEntityReadRejectsTheWholeListQueryBeforeSql() {
        em.createQuery("delete from AccessGrant g where g.subjectKey = :user and g.dimension = :key")
            .setParameter("user", subject)
            .setParameter("key", C5PermissionKeys.entityRead(Nomenclature.class))
            .executeUpdate();
        em.flush();
        SqlCapture.SQL.get().clear();

        assertThatThrownBy(() -> assembly.describe(definition(HQL)))
            .hasMessageContaining("ENTITY-read")
            .hasMessageContaining(Nomenclature.class.getName());
        assertThat(SqlCapture.SQL.get()).noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("string_agg"));
    }

    @Test
    void emptyListIsNullAndDistinctRemovesDuplicateText() throws Exception {
        var description = assembly.describe(definition(HQL));
        var dataset = executor.execute(description, 100, 30_000);

        assertThat(dataset.rows()[0].value("values")).isEqualTo("Деталь X, Деталь Y");
        assertThat(dataset.rows()[2].value("values")).isNull();
    }

    private QueryDefinition definition(String hql) {
        return new QueryDefinition(hql, QuerySource.MANUAL, null, null, java.util.List.of());
    }

    /**
     * Проверенный каталог: в persistence unit приложения есть managed-регистр, поэтому
     * короткий конструктор (каталог без classification sources) здесь неприменим —
     * иначе запрос падает на CLASSIFICATION_SOURCE_MISSING до выполнения CTE.
     */
    private org.ipro.query.QueryBuilderMetadataCatalog catalog(RlsReadGate readGate) {
        var metadata = new org.ipro.metadata.MetadataResolver();
        var types = new org.ipro.metadata.ManagedEntityTypes(factory);
        var managed = new org.ipro.metadata.ManagedEntityCatalog(types);
        var descriptors = new org.ipro.data.EntityDescriptorCatalog(managed,
            new org.ipro.metadata.SectionMetadataRegistry(types, metadata), metadata,
            List.of(), List.of(),
            org.ip.metadata.EntityTypeTestSources.registers(managed, factory, metadata));
        return new org.ipro.query.QueryBuilderMetadataCatalog(types, metadata, readGate,
            () -> subject, descriptors);
    }

    private String dataSql() {
        return SqlCapture.SQL.get().stream()
            .filter(sql -> sql.toLowerCase(Locale.ROOT).contains("string_agg"))
            .reduce((first, second) -> second)
            .orElseThrow();
    }

    private void writeEvidence(String name, org.ipro.query.ReportQueryAssembler description,
                               org.ipro.report.data.ReportDataset dataset, String sql) throws Exception {
        Files.createDirectories(Path.of("outputs/listagg-spike"));
        Files.writeString(Path.of("outputs/listagg-spike/" + name + "-query.hql"), description.jpql() + "\n");
        Files.writeString(Path.of("outputs/listagg-spike/" + name + "-query.jpql"), description.jpql() + "\n");
        Files.writeString(Path.of("outputs/listagg-spike/" + name + "-sql.log"), sql + "\n");
        var rows = new StringBuilder();
        for (var row : dataset.rows()) {
            rows.append(row.toString()).append('\n');
        }
        Files.writeString(Path.of("outputs/listagg-spike/" + name + "-rows.txt"), rows);
    }

    private void writeEvidence(String name, String hql,
                               org.ipro.report.data.ReportDataset dataset, String sql) throws Exception {
        Files.createDirectories(Path.of("outputs/listagg-spike"));
        Files.writeString(Path.of("outputs/listagg-spike/" + name + "-query.hql"), hql + "\n");
        Files.writeString(Path.of("outputs/listagg-spike/" + name + "-sql.log"), sql + "\n");
        var rows = new StringBuilder();
        for (var row : dataset.rows()) {
            rows.append(row.toString()).append('\n');
        }
        Files.writeString(Path.of("outputs/listagg-spike/" + name + "-rows.txt"), rows);
    }

    private void fixtures() {
        var visible = branch("LST-B");
        workshop = workshop("LST-W", "Цех списка", visible);
        var unit = new UnitOfMeasurement("lst-шт", "Штука LST", "LST-PCS");
        em.persist(unit);
        var type = new AccountingType("LST-T", "Тип списка");
        em.persist(type);
        var x = item("N-X", "Деталь X", unit);
        var y = item("N-Y", "Деталь Y", unit);
        var first = document("LST-01", 1);
        positions(first, type, x, y, x);
        positions(document("LST-02", 2), type, y);
        document("LST-03", 3);
        for (Class<?> entity : List.of(AccountingObjectInstallation.class,
            AccountingObjectInstallationItem.class, Nomenclature.class, Workshop.class,
            UnitOfMeasurement.class, AccountingType.class, Branch.class)) {
            grant(C5PermissionKeys.entityRead(entity), null);
        }
        grant("BRANCH", visible.getId());
    }

    private Branch branch(String code) {
        var value = new Branch();
        value.setCode(code);
        value.setName(code);
        em.persist(value);
        return value;
    }

    private Workshop workshop(String code, String name, Branch branch) {
        var value = new Workshop(code, name);
        value.setBranch(branch);
        em.persist(value);
        return value;
    }

    private Nomenclature item(String code, String name, UnitOfMeasurement unit) {
        var value = new Nomenclature(code, name, unit);
        em.persist(value);
        return value;
    }

    private AccountingObjectInstallation document(String number, int day) {
        var value = new AccountingObjectInstallation();
        value.setNumber(number);
        value.setDate(LocalDateTime.of(2026, 10, day, 12, 0));
        value.setWorkshop(workshop);
        em.persist(value);
        return value;
    }

    private void positions(AccountingObjectInstallation document, AccountingType type,
                           Nomenclature... items) {
        for (int index = 0; index < items.length; index++) {
            var row = new AccountingObjectInstallationItem();
            row.setDocument(document);
            row.setAccountingType(type);
            row.setNomenclature(items[index]);
            row.setLineNumber(index + 1);
            em.persist(row);
        }
    }

    private void grant(String dimension, Long valueId) {
        var grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(subject);
        grant.setDimension(dimension);
        grant.setDimensionValueId(valueId);
        grant.setCanRead(true);
        em.persist(grant);
    }
}
