package org.ipro.query;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Tuple;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.cfg.Configuration;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.grouping.GroupAggregation;
import org.ipro.filtergrid.grouping.GroupField;
import org.ipro.filtergrid.projection.CompiledFilter;
import org.ipro.filtergrid.projection.ExecutionContext;
import org.ipro.filtergrid.projection.InMemoryTupleFilterEvaluator;
import org.ipro.filtergrid.projection.MaterializedStrategy;
import org.ipro.filtergrid.projection.ProjectionDefinition;
import org.ipro.filtergrid.projection.ProjectionGroupValuesService;
import org.ipro.filtergrid.projection.ProjectionSortCompiler;
import org.ipro.filtergrid.projection.QueryProvenance;
import org.ipro.filtergrid.projection.SortKey;
import org.ipro.filtergrid.projection.TupleStrategy;
import org.ipro.query.q6.Q6Product;
import org.ipro.query.register.RegisterSliceReportTemplates;
import org.ipro.rls.AccessGrantVersion;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsPolicyDescriptor;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Fq0ProjectionIT {
    @Entity(name = "Fq0Line")
    @FilterDef(name = "FQ0_TENANT", parameters = @ParamDef(name = "allowedIds", type = Long.class))
    @Filter(name = "FQ0_TENANT", condition = "tenant_id in (:allowedIds)")
    static class Line {
        @Id Long id;
        @Column(name = "tenant_id") Long tenant;
        @ManyToOne Q6Product product;
        BigDecimal quantity;
    }

    private static final String LINES = """
            select l.id as rowId, p.code as category, l.quantity as amount
            from Fq0Line l join Q6Product p on p.id = fk(l.product)
            """;
    private static final String WRAPPED = """
            select t.rowId as rowId, t.category as category, t.amount as amount from (%s) t
            """;
    private static final String GROUPED = """
            select p.id as rowId, p.code as category, sum(l.quantity) as amount
            from Fq0Line l join Q6Product p on p.id = fk(l.product)
            group by p.id, p.code having sum(l.quantity) >= 3
            """;
    private static final String PRODUCTS = """
            select p.id as rowId, p.code as category, p.amount as amount from Q6Product p
            """;
    private static final String CTE = """
            with visible as (%s)
            select v.rowId as rowId, v.category as category, v.amount as amount from visible v
            """.formatted(LINES);

    private static final String SLICE_WITH_FK = """
            with latest as (
                select fk(l.product) as productId, max(l.id) as lineId
                from Fq0Line l group by fk(l.product)
            )
            select l.id as rowId, p.code as category, l.quantity as amount
            from latest v join Fq0Line l on l.id = v.lineId
            join Q6Product p on p.id = v.productId
            """;

    private static final String SLICE = SLICE_WITH_FK.replace("fk(l.product)", "l.product.id");

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgresql"})
    void projectionMatrix(String database) {
        try (var factory = factory(database); var session = factory.openSession()) {
            session.beginTransaction();
            var a = product(session, "A", 5);
            var b = product(session, "B", 8);
            line(session, 1L, 1L, a, 2);
            line(session, 2L, 2L, a, 3);
            line(session, 3L, 2L, b, 8);
            session.flush();

            check(session, PRODUCTS, Map.of(), List.of("B", "A"), 13);
            check(session, LINES, Map.of(), List.of("B", "A", "A"), 13);
            check(session, LINES + " where l.quantity >= cast(:minimum as Long)", Map.of("minimum", 3L),
                    List.of("B", "A"), 11);
            check(session, WRAPPED.formatted(GROUPED), Map.of(), List.of("B", "A"), 13);
            check(session, CTE, Map.of(), List.of("B", "A", "A"), 13);
            assertThatThrownBy(() -> new TupleStrategy(definition(SLICE_WITH_FK, Map.of()), session)
                    .fetch(context(Map.of(), null))).isInstanceOf(ClassCastException.class);
            checkSlice(session, database, List.of("B", "A"), 11);

            var originalOr = LINES + " where p.code = 'A' or p.code = 'B'";
            var unsafe = definition(originalOr, Map.of());
            var raw = new TupleStrategy(unsafe, session);
            assertThat(categories(raw, context(Map.of(), "l.quantity >= 8"))).containsExactly("B");
            check(session, WRAPPED.formatted(originalOr), Map.of(), "t.amount >= 8", List.of("B"), 8);
            check(session, WRAPPED.formatted(GROUPED), Map.of(), "t.amount >= 6", List.of("B"), 8);
            checkMemoryFilters(session);
            checkReferencePresentation(session, a.getId(), b.getId());

            var many = """
                    select l.id as rowId, p.code as category, l.quantity as amount
                    from Q6Product p join Fq0Line l on fk(l.product) = p.id
                    """;
            check(session, many, Map.of(), List.of("B", "A", "A"), 13);
            assertThat(session.createQuery("select p.id from Q6Product p join Fq0Line l on fk(l.product) = p.id", Long.class)
                    .getResultList()).containsExactlyInAnyOrder(a.getId(), a.getId(), b.getId());

            var permissions = mock(C5PermissionEvaluator.class);
            when(permissions.isEntityReadPermitted("fq0", Q6Product.class)).thenReturn(true);
            when(permissions.isEntityReadPermitted("fq0", Line.class)).thenReturn(true);
            var registry = new RlsDimensionRegistry("org.ipro.query") {
                @Override public Set<String> dimensions() { return Set.of("FQ0_TENANT"); }
                @Override public RlsDimensionKind kindOf(String dimension) { return RlsDimensionKind.FILTERABLE; }
            };
            var access = mock(AccessService.class);
            when(access.getReadableIds("PRODUCT", "fq0")).thenReturn(List.of(a.getId()));
            var associationRegistry = new RlsDimensionRegistry("org.ipro.query") {
                @Override public RlsPolicyDescriptor policyOf(Class<?> type) {
                    if (type == Q6Product.class) {
                        return new RlsPolicyDescriptor(type, true, Map.of("PRODUCT", RlsDimensionKind.FILTERABLE),
                                Map.of("PRODUCT", new RlsPolicyDescriptor.ValueRule(List.of("id"), false, false)));
                    }
                    return super.policyOf(type);
                }
            };
            var authorizer = new ReportReadAuthorizer(factory, permissions, () -> "fq0", List.of(),
                    access, associationRegistry);
            assertThat(authorizer.violations("select l.product.code as productCode from Fq0Line l", Map.of()))
                    .anyMatch(error -> error.contains("RLS_ASSOCIATION_JOIN_REQUIRED"));
            assertThat(authorizer.violations("select p as product from Q6Product p", Map.of()))
                    .anyMatch(error -> error.contains("scalar"));
            assertThat(authorizer.violations(LINES, Map.of())).isEmpty();

            var allowed = new AtomicReference<>(List.of(1L, 2L));
            var activator = new RlsFilterActivator(registry, (dimension, user) -> allowed.get(), () -> "fq0");
            var subquery = PRODUCTS + " where p.id in (select l.product.id from Fq0Line l)";
            activator.ensureRlsEnabled(session);
            check(session, subquery, Map.of(), List.of("B", "A"), 13);
            check(session, CTE, Map.of(), List.of("B", "A", "A"), 13);
            check(session, WRAPPED.formatted(LINES), Map.of(), List.of("B", "A", "A"), 13);
            allowed.set(List.of(1L));
            AccessGrantVersion.bump();
            activator.ensureRlsEnabled(session);
            check(session, subquery, Map.of(), List.of("A"), 5);
            check(session, CTE, Map.of(), List.of("A"), 2);
            checkSlice(session, database, List.of("A"), 2);
            check(session, WRAPPED.formatted(LINES), Map.of(), List.of("A"), 2);
            session.getTransaction().rollback();
        }
    }

    private static void checkSlice(EntityManager em, String database, List<String> expected, long total) {
        if (database.equals("h2")) {
            var strategy = new TupleStrategy(definition(SLICE, Map.of()), em);
            var context = context(Map.of(), null);
            assertThat(categories(strategy, context)).containsExactlyElementsOf(expected);
            assertThatThrownBy(() -> strategy.count(context)).isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("Can't emulate lateral join");
        } else {
            check(em, SLICE, Map.of(), expected, total);
        }
    }

    @Test
    void measureMaterializedHeap() {
        assumeTrue(Boolean.getBoolean("fq0.measureHeap"), "Opt-in heap measurement");
        try (var factory = factory("h2"); var session = factory.openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery("""
                    insert into Q6Product (id, code, amount)
                    select x, 'Product-' || x, x * 0.25 from system_range(1, 100000)
                    """).executeUpdate();
            session.getTransaction().commit();
            var query = definition(PRODUCTS + " where p.id <= :maximum", Map.of("maximum", 0L));
            var strategy = new MaterializedStrategy(query, session);
            strategy.materialize(ExecutionContext.of(Map.of("maximum", 1000L), 0, 1), 1000);
            for (int size : List.of(10_000, 50_000, 100_000)) {
                long before = usedHeapAfterGc();
                var rows = strategy.materialize(ExecutionContext.of(Map.of("maximum", (long) size), 0, 1),
                        size + 1);
                long retained = usedHeapAfterGc() - before;
                assertThat(rows).hasSize(size);
                System.out.printf("FQ-0 heap: %d rows, %d bytes retained%n", size, retained);
                java.lang.ref.Reference.reachabilityFence(rows);
            }
        }
    }

    private static long usedHeapAfterGc() {
        System.gc();
        return java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void checkReferencePresentation(EntityManager em, Long first, Long second) {
        var catalog = mock(QueryBuilderMetadataCatalog.class);
        var product = new QueryBuilderMetadataCatalog.Entity("Q6Product", Q6Product.class, List.of(
                new QueryBuilderMetadataCatalog.Field("id", "ID", Long.class, true),
                new QueryBuilderMetadataCatalog.Field("code", "Code", String.class, false)), List.of());
        when(catalog.root("Q6Product")).thenReturn(product);
        when(catalog.roots()).thenReturn(List.of(product));
        var compiled = new ReportQueryLanguage(catalog, mock(RegisterSliceReportTemplates.class))
                .parse("select p.code as category, Представление(p) as label from Q6Product p").compiled();
        var presentation = compiled.presentations().columns().getFirst();
        assertThat(presentation.entityType()).isEqualTo(Q6Product.class);
        assertThat(presentation.name()).isEqualTo("label");

        var id = presentation.idColumn();
        var definition = ProjectionDefinition.define("fq0-presentation").hql(compiled.jpql())
                .textColumn("category", "Category").idColumn(id, "ID").rowKey(id).build();
        var strategy = new TupleStrategy(definition, em);
        assertThat(strategy.fetch(ExecutionContext.of(Map.of(), 0, 20)).stream().map(Tuple.class::cast)
                .map(row -> row.get(id, Long.class))).containsExactly(first, second);
        assertThatThrownBy(() -> ProjectionSortCompiler.compileOrderBy(definition, List.of(SortKey.asc("label"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void checkMemoryFilters(EntityManager em) {
        var definition = definition(LINES, Map.of());
        assertThatThrownBy(() -> materialize(em, definition, 2)).isInstanceOf(IllegalStateException.class);
        var rows = materialize(em, definition, 3);
        assertThat(rows).hasSize(3);
        var evaluator = new InMemoryTupleFilterEvaluator(definition);
        var literal = new FilterConditionNode(new FilterCondition("amount", FilterOperator.GE, "8", null,
                FilterDataType.NUMBER));
        var parameter = new FilterConditionNode(new FilterCondition("amount", FilterOperator.GE, ":minimum", null,
                FilterDataType.NUMBER));
        var subquery = new FilterConditionNode(new FilterCondition("rowId", FilterOperator.IN,
                "@subquery:select l.id from Fq0Line l", null, FilterDataType.NUMBER));

        assertThat(evaluator.isLocallyEvaluable(literal, Map.of())).isTrue();
        assertThat(evaluator.isLocallyEvaluable(parameter, Map.of())).isFalse();
        assertThat(evaluator.isLocallyEvaluable(parameter, Map.of("minimum", 8L))).isTrue();
        assertThat(evaluator.isLocallyEvaluable(subquery, Map.of())).isFalse();
        assertThat(evaluator.filter(literal, rows, Map.of())).extracting(row -> row.get("category"))
                .containsExactly("B");
        assertThat(evaluator.filter(parameter, rows, Map.of("minimum", 8L)))
                .extracting(row -> row.get("category")).containsExactly("B");
    }

    private static List<Tuple> materialize(EntityManager em, ProjectionDefinition definition, int maxRows) {
        var strategy = new MaterializedStrategy(definition, em);
        var rows = strategy.materialize(context(Map.of(), null), maxRows + 1);
        if (rows.size() > maxRows) {
            throw new IllegalStateException("Full result exceeds " + maxRows + " rows");
        }
        return rows;
    }

    static org.hibernate.SessionFactory factory(String database) {
        var configuration = new Configuration().addAnnotatedClass(Q6Product.class).addAnnotatedClass(Line.class);
        configuration.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        configuration.setProperty("hibernate.hbm2ddl.halt_on_error", "true");
        if (database.equals("postgresql")) {
            var url = System.getenv("FQ0_TEST_JDBC_URL");
            assumeTrue(url != null, "FQ0_TEST_JDBC_URL must point to a dedicated test database");
            if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/fq0_[a-f0-9]{32}")) {
                throw new IllegalArgumentException("Expected a dedicated local fq0 database");
            }
            configuration.setProperty("hibernate.connection.url", url);
            configuration.setProperty("hibernate.connection.username", System.getenv("FQ0_TEST_JDBC_USERNAME"));
            configuration.setProperty("hibernate.connection.password", System.getenv("FQ0_TEST_JDBC_PASSWORD"));
        } else {
            configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:fq0");
            configuration.setProperty("hibernate.connection.username", "sa");
        }
        return configuration.buildSessionFactory();
    }

    static Q6Product product(EntityManager em, String code, long amount) {
        var product = new Q6Product();
        product.setCode(code);
        product.setAmount(BigDecimal.valueOf(amount));
        em.persist(product);
        return product;
    }

    static void line(EntityManager em, Long id, Long tenant, Q6Product product, long amount) {
        var line = new Line();
        line.id = id;
        line.tenant = tenant;
        line.product = product;
        line.quantity = BigDecimal.valueOf(amount);
        em.persist(line);
    }

    private static ProjectionDefinition definition(String hql, Map<String, Object> parameters) {
        var builder = ProjectionDefinition.define("fq0").queryProvenance(QueryProvenance.REGISTERED).hql(hql).idColumn("rowId", "ID")
                .textColumn("category", "Category").numberColumn("amount", "Amount")
                .rowKey("rowId");
        parameters.keySet().forEach(builder::requiredLongParameter);
        return builder.build();
    }

    private static ExecutionContext context(Map<String, Object> parameters, String predicate) {
        return new ExecutionContext(parameters, new CompiledFilter(predicate, Map.of()), 0, 20,
                List.of(SortKey.desc("amount")));
    }

    private static List<String> categories(TupleStrategy strategy, ExecutionContext context) {
        return strategy.fetch(context).stream().map(Tuple.class::cast)
                .map(row -> row.get("category", String.class)).toList();
    }

    private static void check(EntityManager em, String hql, Map<String, Object> parameters,
                              List<String> expected, long total) {
        check(em, hql, parameters, null, expected, total);
    }

    private static void check(EntityManager em, String hql, Map<String, Object> parameters, String predicate,
                              List<String> expected, long total) {
        var definition = definition(hql, parameters);
        var strategy = new TupleStrategy(definition, em);
        var context = context(parameters, predicate);
        assertThat(categories(strategy, context)).containsExactlyElementsOf(expected);
        assertThat(strategy.count(context)).isEqualTo(expected.size());
        assertThat(new BigDecimal(strategy.sum(context, "amount").toString())).isEqualByComparingTo(
                BigDecimal.valueOf(total));

        var values = new ProjectionGroupValuesService(definition, null, em);
        values.setQueryParameters(parameters);
        values.setBaseFilter(context.compiledFilter());
        var field = new GroupField<Tuple, String>("category", "Category", "category",
                row -> row.get("category", String.class), true);
        var distinct = expected.stream().distinct().sorted().toList();
        var groups = values.fetchDistinct(field, List.of(), 0, 20,
                List.of(new GroupAggregation("amount", "Sum", GroupAggregation.AggFunction.SUM)));
        assertThat(groups.stream().map(group -> new BigDecimal(group.aggregates().getFirst().toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(BigDecimal.valueOf(total));
        assertThat(groups.stream().map(row -> row.value().toString())).containsExactlyElementsOf(distinct);
        assertThat(groups.stream().map(row -> row.matchingCount())).containsExactlyElementsOf(distinct.stream()
                .map(value -> expected.stream().filter(value::equals).count()).toList());
        assertThat(values.fetchDistinctAll(field, List.of(), 0, 20).stream()
                .map(row -> row.value().toString())).containsExactlyElementsOf(distinct);
        assertThat(values.countDistinctAll(field, List.of())).isEqualTo(distinct.size());
    }
}

