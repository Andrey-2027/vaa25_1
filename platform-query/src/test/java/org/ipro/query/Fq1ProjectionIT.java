package org.ipro.query;

import jakarta.persistence.Tuple;
import org.ipro.filtergrid.FooterAggregateKind;
import org.ipro.filtergrid.FooterAggregateRequest;
import org.ipro.filtergrid.grouping.GroupField;
import org.ipro.filtergrid.projection.ExecutionContext;
import org.ipro.filtergrid.projection.ProjectionDefinition;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.filtergrid.projection.ProjectionGroupValuesService;
import org.ipro.query.model.QueryLimits;
import org.ipro.query.q6.Q6Product;
import org.ipro.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.rls.AccessGrantVersion;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Fq1ProjectionIT {
    private static final String HQL = """
            select l.id as rowId, l.tenant as tenant, l.quantity as amount from Fq0Line l
            """;

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgresql"})
    void everyReadUsesCurrentRightsInItsOwnTransaction(String database) {
        try (var factory = Fq0ProjectionIT.factory(database); var seed = factory.openSession()) {
            seed.beginTransaction();
            var product = Fq0ProjectionIT.product(seed, "A", 5);
            Fq0ProjectionIT.line(seed, 1L, 1L, product, 2);
            Fq0ProjectionIT.line(seed, 2L, 2L, product, 8);
            seed.getTransaction().commit();

            var em = SharedEntityManagerCreator.createSharedEntityManager(factory);
            var transactions = new JpaTransactionManager(factory);
            var access = mock(AccessService.class);
            var permissions = mock(C5PermissionEvaluator.class);
            when(permissions.isEntityReadPermitted("fq1", Fq0ProjectionIT.Line.class)).thenAnswer(call -> {
                assertThat(em.isJoinedToTransaction()).isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                return true;
            });
            var dimensions = new RlsDimensionRegistry("org.ipro.query") {
                @Override public Set<String> dimensions() { return Set.of("FQ0_TENANT"); }
                @Override public RlsDimensionKind kindOf(String name) { return RlsDimensionKind.FILTERABLE; }
            };
            var allowed = new AtomicReference<>(List.of(1L, 2L));
            var rls = new RlsFilterActivator(dimensions, (name, user) -> allowed.get(), () -> "fq1");
            var authorizer = new ReportReadAuthorizer(factory, permissions, () -> "fq1", List.of(),
                    access, dimensions);
            var analyzer = org.mockito.Mockito.spy(new SqmQuerySemanticAnalyzer(factory));
            var guard = new ReportQueryGuard(analyzer, access,
                    dimensions, () -> "fq1", factory, authorizer);
            var quota = new ReportRunQuota(1);
            var reads = new ReportProjectionReads(em, transactions, guard, () -> "fq1", rls, quota, 1);
            var lazy = definition(ProjectionExecutionMode.LAZY);
            var path = reads.create(lazy, new QueryLimits(1, 5000));
            var strategy = path.strategy();
            var context = ExecutionContext.of(Map.of(), 0, 20);
            var groups = new ProjectionGroupValuesService(lazy, null, em, path.policy());
            var field = new GroupField<Tuple, Long>("tenant", "Tenant", "tenant",
                    row -> row.get("tenant", Long.class), true);

            assertThat(strategy.fetch(context)).hasSize(1);
            path.policy().execute(em, lazy, HQL, () -> em.createQuery(HQL, Tuple.class), query -> {
                assertThat(query.getHints()).containsEntry("jakarta.persistence.query.timeout", 5000);
                if (database.equals("postgresql")) {
                    assertThat(em.createNativeQuery("show statement_timeout").getSingleResult()).isEqualTo("5s");
                }
                return query.getResultList();
            });
            assertThat(strategy.count(context)).isEqualTo(2);

            var compiler = new org.ipro.filtergrid.projection.ProjectionFilterCompiler(
                    fieldName -> new org.ipro.filtergrid.filter.FilterFieldResolver.ResolvedFilterField(
                            fieldName, "Amount", BigDecimal.class,
                            org.ipro.filtergrid.filter.FilterDataType.NUMBER, true));
            var filter = new org.ipro.filtergrid.filter.FilterConditionNode(
                    new org.ipro.filtergrid.filter.FilterCondition("l.quantity",
                            org.ipro.filtergrid.filter.FilterOperator.GE, "2", null,
                            org.ipro.filtergrid.filter.FilterDataType.NUMBER));
            var occupied = Map.<String, Object>of("visualFilter_1", 9);
            var first = compiler.compile(filter, occupied);
            var second = compiler.compile(filter, occupied);
            assertThat(first.predicate()).contains(":visualFilter_2");
            assertThat(second).isEqualTo(first);
            var firstPage = new ExecutionContext(Map.of(), first, 0, 1, List.of());
            var secondPage = new ExecutionContext(Map.of(), second, 1, 1, List.of());
            assertThat(strategy.fetch(firstPage).stream().map(Tuple.class::cast).map(row -> row.get("rowId")))
                    .containsExactly(1L);
            assertThat(strategy.fetch(secondPage).stream().map(Tuple.class::cast).map(row -> row.get("rowId")))
                    .containsExactly(2L);
            var pageHql = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(analyzer, org.mockito.Mockito.atLeastOnce()).analyze(pageHql.capture());
            var filteredHql = pageHql.getAllValues().stream().filter(hql -> hql.contains(":visualFilter_2")).toList();
            assertThat(filteredHql).hasSize(1);
            var memory = reads.create(definition(ProjectionExecutionMode.IN_MEMORY), QueryLimits.DEFAULT);
            assertThatThrownBy(() -> memory.strategy().fetch(context)).hasMessageContaining("Результат больше 1");
            assertThat(quota.activeRuns()).isZero();
            var slot = quota.tryAcquire(0);
            try {
                assertThatThrownBy(() -> memory.strategy().fetch(context)).hasMessageContaining("уже выполняется");
            } finally {
                slot.release();
            }

            var revision = memory.policy().accessRevision();
            allowed.set(List.of(1L));
            AccessGrantVersion.bump();
            assertThat(memory.policy().accessRevision()).isNotEqualTo(revision);
            assertThat(strategy.fetch(secondPage)).isEmpty();
            org.mockito.Mockito.verify(analyzer).analyze(filteredHql.getFirst());
            assertThat(memory.strategy().fetch(context)).hasSize(1);
            assertThat(strategy.fetch(context).stream().map(Tuple.class::cast).map(row -> row.get("rowId")))
                    .containsExactly(1L);
            assertThat(strategy.count(context)).isEqualTo(1);
            assertThat(new BigDecimal(strategy.sum(context, "amount").toString())).isEqualByComparingTo("2");
            assertThat(strategy.aggregates(context, List.of(new FooterAggregateRequest("amount", "amount", FooterAggregateKind.SUM))))
                    .singleElement().satisfies(sum -> assertThat(new BigDecimal(sum.toString())).isEqualByComparingTo("2"));
            assertThat(groups.fetchDistinct(field, List.of(), 0, 20, List.of()).stream()
                    .map(row -> row.value())).containsExactly(1L);
            assertThat(groups.fetchDistinctAll(field, List.of(), 0, 20).stream()
                    .map(row -> row.value())).containsExactly(1L);
            assertThat(groups.countDistinctAll(field, List.of())).isEqualTo(1);
            assertThat(groups.countMatching(List.of())).isEqualTo(1);

            org.mockito.Mockito.doReturn(false).when(permissions).isEntityReadPermitted("fq1", Fq0ProjectionIT.Line.class);
            assertThatThrownBy(() -> strategy.fetch(firstPage)).hasMessageContaining("ENTITY-read");
            org.mockito.Mockito.verify(analyzer).analyze(filteredHql.getFirst());
            assertThatThrownBy(() -> groups.countMatching(List.of())).hasMessageContaining("ENTITY-read");
        }
    }

    private static ProjectionDefinition definition(ProjectionExecutionMode mode) {
        return ProjectionDefinition.define("fq1").hql(HQL)
                .idColumn("rowId", "ID").numberColumn("tenant", "Tenant")
                .numberColumn("amount", "Amount").rowKey("rowId").mode(mode).build();
    }
}

