package org.ipro.query;

import jakarta.persistence.Tuple;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.grouping.GroupField;
import org.ipro.filtergrid.projection.ExecutionContext;
import org.ipro.filtergrid.projection.ColumnMeta;
import org.ipro.filtergrid.projection.NullKeyPolicy;
import org.ipro.filtergrid.projection.ProjectionColumnResolver;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.filtergrid.projection.ProjectionFilterCompiler;
import org.ipro.filtergrid.projection.ProjectionGroupValuesService;
import org.ipro.filtergrid.projection.QueryProvenance;
import org.ipro.filtergrid.projection.TupleStrategy;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.param.EntityParamRefresher;
import org.ipro.query.param.ReportParamResolver;
import org.ipro.query.param.ResolvedParams;
import org.ipro.query.q6.Q6Product;
import org.ipro.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Fq2ProjectionIT {
    @Test
    void describesProjectionWithRequiredPeriodWithoutResolvingValuesOrCheckingReadPermissions() {
        try (var factory = Fq0ProjectionIT.factory("h2")) {
            var access = mock(AccessService.class);
            var permissions = mock(C5PermissionEvaluator.class);
            var resolver = mock(ReportParamResolver.class);
            var refresher = mock(EntityParamRefresher.class);
            var dimensions = new RlsDimensionRegistry("org.ipro.query");
            var authorizer = new ReportReadAuthorizer(factory, permissions, () -> "author", List.of(), access, dimensions);
            var guard = new ReportQueryGuard(new SqmQuerySemanticAnalyzer(factory), access, dimensions,
                    () -> "author", factory, authorizer);
            var compiler = new QueryProjectionCompiler(factory, new ReportQueryAssemblyService(guard, resolver, refresher), guard);
            var query = new QueryDefinition("""
                    select l.tenant as workshop, sum(l.quantity) as quantity
                    from Fq0Line l where l.quantity between :periodFrom and :periodTo group by l.tenant
                    """, null, null, null, List.of(QueryParameter.required("period")
                    .withKind(org.ipro.query.model.QueryParameterKind.RANGE)));

            assertThat(compiler.describe(query)).extracting(ColumnMeta::key).containsExactly("workshop", "quantity");
            assertThatThrownBy(() -> compiler.describe(query.withParameters(List.of())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("periodFrom");
            assertThatThrownBy(() -> compiler.describe(query("select p.code from Q6Product p")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("алиас");
            assertThatThrownBy(() -> compiler.describe(query("select p.code as code from Q6Product p limit 1")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LIMIT");
            verifyNoInteractions(access, permissions, resolver, refresher);
        }
    }

    @Test
    void describesVisualAndPresentationQueriesEvenWhenViewerHasNoReadAccess() {
        try (var factory = Fq0ProjectionIT.factory("h2")) {
            var access = mock(AccessService.class);
            var readGate = mock(org.ipro.rls.RlsReadGate.class);
            var permissions = mock(C5PermissionEvaluator.class);
            var resolver = mock(ReportParamResolver.class);
            var refresher = mock(EntityParamRefresher.class);
            var dimensions = new RlsDimensionRegistry("org.ipro.query");
            var authorizer = new ReportReadAuthorizer(factory, permissions, () -> "viewer", List.of(), access, dimensions);
            var guard = new ReportQueryGuard(new SqmQuerySemanticAnalyzer(factory), access, dimensions,
                    () -> "viewer", factory, authorizer);
            var catalog = new QueryBuilderMetadataCatalog(factory, new org.ipro.metadata.MetadataResolver(),
                    readGate, () -> "viewer");
            var recipes = new org.ipro.query.register.RegisterSliceReportTemplates(factory, null, catalog, null);
            var assembly = new ReportQueryAssemblyService(guard, resolver, refresher, catalog);
            assembly.useQueryLanguage(new ReportQueryLanguage(catalog, recipes));
            var compiler = new QueryProjectionCompiler(factory, assembly, guard);
            var visual = new VisualQueryTextParser(catalog.forStructureValidation())
                    .parsePackage("select p.code as code from Q6Product p").queryPackage();
            var query = new QueryDefinition("", org.ipro.query.model.QuerySource.VISUAL,
                    new VisualQueryPackageJsonCodec().write(visual), null, List.of());

            assertThat(compiler.describe(query)).extracting(ColumnMeta::key).containsExactly("code");
            assertThat(compiler.describe(query("select Представление(p) as label from Q6Product p")))
                    .singleElement().satisfies(column -> {
                        assertThat(column.key()).startsWith("presentation_id_");
                        assertThat(column.referenceType()).isEqualTo(Q6Product.class);
                    });
            verifyNoInteractions(access, readGate, permissions, resolver, refresher);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgresql"})
    void checkedConversionPreservesRowsAndGroupSemantics(String database) {
        try (var factory = Fq0ProjectionIT.factory(database); var session = factory.openSession()) {
            session.beginTransaction();
            var a = Fq0ProjectionIT.product(session, "A", 5);
            var b = Fq0ProjectionIT.product(session, "B", 8);
            Fq0ProjectionIT.line(session, 1L, 1L, a, 2);
            Fq0ProjectionIT.line(session, 2L, 1L, a, 3);
            Fq0ProjectionIT.line(session, 3L, 1L, b, 8);
            session.flush();

            var access = mock(AccessService.class);
            var permissions = mock(C5PermissionEvaluator.class);
            when(permissions.isEntityReadPermitted("fq2", Q6Product.class)).thenReturn(true);
            when(permissions.isEntityReadPermitted("fq2", Fq0ProjectionIT.Line.class)).thenReturn(true);
            var dimensions = new RlsDimensionRegistry("org.ipro.query");
            var authorizer = new ReportReadAuthorizer(factory, permissions, () -> "fq2", List.of(), access, dimensions);
            var guard = new ReportQueryGuard(new SqmQuerySemanticAnalyzer(factory), access, dimensions,
                    () -> "fq2", factory, authorizer);
            var context = ReportContext.empty("fq2");
            var resolver = mock(ReportParamResolver.class);
            when(resolver.resolve(anyList(), eq(context), anyMap())).thenAnswer(call ->
                    ResolvedParams.ok(call.getArgument(2)));
            var assembly = new ReportQueryAssemblyService(guard, resolver, mock(EntityParamRefresher.class));
            var catalog = mock(QueryBuilderMetadataCatalog.class);
            var product = new QueryBuilderMetadataCatalog.Entity("Q6Product", Q6Product.class,
                    List.of(new QueryBuilderMetadataCatalog.Field("id", "ID", Long.class, true)), List.of());
            when(catalog.roots()).thenReturn(List.of(product));
            when(catalog.root("Q6Product")).thenReturn(product);
            assembly.useQueryLanguage(new ReportQueryLanguage(catalog,
                    mock(org.ipro.query.register.RegisterSliceReportTemplates.class)));
            var compiler = new QueryProjectionCompiler(factory, assembly, guard);

            var scalar = compiler.compile("products", query("""
                    select p.id as productId, p.code as code, p.amount as amount
                    from Q6Product p where p.code = 'A' or p.code = 'B'
                    """), context, Map.of(), ProjectionExecutionMode.LAZY, 0, "productId");
            assertThat(scalar.definition().queryProvenance()).isEqualTo(QueryProvenance.REGISTERED);
            assertThat(scalar.definition().rowKey()).containsExactly("productId");
            assertThat(scalar.commandObject().entityType()).isEqualTo(Q6Product.class);
            assertThat(scalar.definition().column("productId").referenceType()).isEqualTo(Q6Product.class);
            assertThat(scalar.definition().column("productId").aggregatable()).isFalse();
            assertThat(scalar.definition().column("amount").aggregatable()).isTrue();
            assertRead(session, scalar, "code", "B", 8);

            var grouped = compiler.compile("grouped", query("""
                    select l.product.id as productId, sum(l.quantity) as amount
                    from Fq0Line l group by l.product.id having sum(l.quantity) >= 3
                    """), context, Map.of(), ProjectionExecutionMode.LAZY, 0, null);
            assertThat(grouped.definition().rowKey()).containsExactly("productId");
            assertThat(grouped.definition().rowKeyNullPolicy()).isEqualTo(NullKeyPolicy.ALLOW);
            assertRead(session, grouped, "productId", b.getId(), 8);

            var many = compiler.compile("many", query("""
                    select p.id as productId, l.quantity as amount
                    from Q6Product p join Fq0Line l on l.product.id = p.id
                    """), context, Map.of(), ProjectionExecutionMode.LAZY, 0, "productId");
            assertThat(many.definition().rowKey()).isEmpty();
            assertThat(many.commandObject().column()).isEqualTo("productId");
            assertRead(session, many, "productId", b.getId(), 8);

            var source = new QueryDefinition("""
                    select p.id as productId, p.amount as amount
                    from Q6Product p where p.amount >= :minimum
                    """, null, null, null, List.of(new QueryParameter("minimum")));
            var parameterized = compiler.compile("minimum", source, context, Map.of("minimum", 6),
                    ProjectionExecutionMode.LAZY, 0, null);
            assertThat(parameterized.bindings()).containsEntry("minimum", 6);
            assertRead(session, parameterized, "productId", b.getId(), 8);

            var cte = compiler.compile("cte", query("""
                    with amounts as (select l.id as rowId, l.quantity as amount from Fq0Line l)
                    select a.rowId as rowId, a.amount as amount from amounts a
                    """), context, Map.of(), ProjectionExecutionMode.LAZY, 0, null);
            assertThat(cte.definition().rowKey()).isEmpty();
            assertRead(session, cte, "rowId", 3L, 8);

            var subquery = compiler.compile("subquery", query("""
                    select p.id as productId, p.amount as amount from Q6Product p
                    where p.id in (select l.product.id from Fq0Line l)
                    """), context, Map.of(), ProjectionExecutionMode.LAZY, 0, null);
            assertThat(subquery.definition().rowKey()).containsExactly("productId");
            assertRead(session, subquery, "productId", b.getId(), 8);

            var toOne = compiler.compile("toOne", query("""
                    select l.id as rowId, p.id as productId, l.quantity as amount
                    from Fq0Line l join l.product p
                    """), context, Map.of(), ProjectionExecutionMode.LAZY, 0, "productId");
            assertThat(toOne.definition().rowKey()).containsExactly("rowId");
            assertThat(toOne.commandObject().column()).isEqualTo("productId");
            assertRead(session, toOne, "rowId", 3L, 8);

            Fq0ProjectionIT.line(session, 4L, 1L, null, 9);
            session.flush();
            var nullGroup = compiler.compile("nullGroup", query("""
                    select l.product.id as productId, sum(l.quantity) as amount
                    from Fq0Line l group by l.product.id
                    """), context, Map.of(), ProjectionExecutionMode.LAZY, 0, null);
            var nullDefinition = nullGroup.definition();
            var nullRows = new TupleStrategy(nullDefinition, session).fetch(ExecutionContext.of(Map.of(), 0, 10));
            nullRows.stream().map(Tuple.class::cast).forEach(nullDefinition::validateSchema);
            var groupService = new ProjectionGroupValuesService(nullDefinition, null, session);
            var groupField = new GroupField<Tuple, Long>("productId", "productId", "productId",
                    row -> row.get("productId", Long.class), true);
            assertThat(groupService.fetchDistinctAll(groupField, List.of(), 0, 10)).anySatisfy(group -> {
                assertThat(group.value()).isNull();
                assertThat(group.matchingCount()).isEqualTo(1);
            });

            var whole = compiler.compile("entity", query("select p from Q6Product p"),
                    context, Map.of(), ProjectionExecutionMode.LAZY, 0, null);
            assertThat(whole.presentations().columns()).singleElement()
                    .satisfies(column -> assertThat(column.entityType()).isEqualTo(Q6Product.class));
            assertThat(whole.definition().rowKey()).hasSize(1);

            assertThatThrownBy(() -> compiler.compile("invalid", query("select p as entity, p.id as id from Q6Product p"),
                    context, Map.of(), ProjectionExecutionMode.LAZY, 0, null)).hasMessageContaining("scalar");
            assertThatThrownBy(() -> compiler.compile("invalid-command",
                    query("select p.code as code from Q6Product p"), context, Map.of(),
                    ProjectionExecutionMode.LAZY, 0, "code")).hasMessageContaining("идентификатор");
            session.getTransaction().rollback();
        }
    }

    private static QueryDefinition query(String hql) {
        return new QueryDefinition(hql, null, null, null, List.of());
    }

    private static void assertRead(jakarta.persistence.EntityManager em,
                                   QueryProjectionCompiler.PreparedProjection projection,
                                   String key, Object expected, int sum) {
        var definition = projection.definition();
        var compiler = new ProjectionFilterCompiler(new ProjectionColumnResolver(definition));
        var filter = compiler.compile(new FilterConditionNode(
                new FilterCondition("amount", FilterOperator.GE, "6", null, FilterDataType.NUMBER)),
                projection.bindings());
        var context = new ExecutionContext(projection.bindings(), filter, 0, 10, List.of());
        var strategy = new TupleStrategy(definition, em);
        assertThat(strategy.fetch(context).stream().map(Tuple.class::cast).map(row -> row.get(key)))
                .containsExactly(expected);
        assertThat(strategy.count(context)).isEqualTo(1);
        assertThat(new java.math.BigDecimal(strategy.sum(context, "amount").toString())).isEqualByComparingTo(
                java.math.BigDecimal.valueOf(sum));
        var groups = new ProjectionGroupValuesService(definition, compiler, em);
        groups.setQueryParameters(projection.bindings());
        groups.setBaseFilter(new org.ipro.filtergrid.projection.CompiledFilter(
                "t." + filter.predicate(), filter.bindings()));
        var field = new GroupField<Tuple, Object>(key, key, key, row -> row.get(key), true);
        assertThat(groups.fetchDistinct(field, List.of(), 0, 10, List.of())).singleElement().satisfies(group -> {
            assertThat(group.value()).isEqualTo(expected);
            assertThat(group.matchingCount()).isEqualTo(1);
        });
    }
}

