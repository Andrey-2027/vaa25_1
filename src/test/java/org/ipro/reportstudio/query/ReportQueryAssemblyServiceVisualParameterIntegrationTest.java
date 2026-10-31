package org.ipro.reportstudio.query;

import org.ipro.query.*;
import org.ipro.query.param.*;
import org.ipro.query.register.*;
import org.ipro.query.sqm.*;

import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QuerySource;
import org.ipro.query.param.EntityParamRefresher;
import org.ipro.query.param.ReportParamResolver;
import org.ipro.query.param.ResolvedParams;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportQueryAssemblyServiceVisualParameterIntegrationTest {
    @Test
    void whereRuntimeBindingIsResolvedAndPendingTextLiteralRemainsAnOrdinaryBinding() {
        var guard = mock(ReportQueryGuard.class);
        when(guard.guard(any(String.class), anySet(), any())).thenReturn(GuardResult.allowed(
            new Analysis(List.of(), List.of(), List.of(), java.util.Set.of("code", "visualFilter_1"))));
        var resolver = mock(ReportParamResolver.class);
        when(resolver.resolve(any(), any(), any())).thenReturn(ResolvedParams.ok(Map.of("code", "A")));
        var conditions = new org.ipro.filtergrid.filter.FilterGroup(org.ipro.filtergrid.filter.LogicalOperator.AND, List.of(
            new org.ipro.filtergrid.filter.FilterConditionNode(new org.ipro.filtergrid.filter.FilterCondition("p.code",
                org.ipro.filtergrid.filter.FilterOperator.EQ, ":code", null, org.ipro.filtergrid.filter.FilterDataType.TEXT)),
            new org.ipro.filtergrid.filter.FilterConditionNode(new org.ipro.filtergrid.filter.FilterCondition("p.name",
                org.ipro.filtergrid.filter.FilterOperator.NE, VisualQueryCompiler.PENDING_PARAM, null, org.ipro.filtergrid.filter.FilterDataType.TEXT))));
        var definition = new VisualQueryDefinition(3, "Product", "p",
            List.of(new VisualQueryDefinition.SelectField("p.code", "code")), List.of(), List.of(), List.of(),
            List.of(), null, conditions, List.of(new VisualQueryDefinition.Parameter("code", "TEXT", true, null)));
        var query = new QueryDefinition("", QuerySource.VISUAL,
            new VisualQueryDefinitionJsonCodec().write(definition), null, List.of());
        var result = new ReportQueryAssemblyService(guard, resolver, mock(EntityParamRefresher.class))
            .assemble(query, null, Map.of("code", "A"));
        assertThat(result.bindings()).containsEntry("code", "A")
            .containsEntry("visualFilter_1", VisualQueryCompiler.PENDING_PARAM);
    }

    @Test
    void assemblesVisualQueryAndPreservesParameterBinding() {
        var guard = mock(ReportQueryGuard.class);
        var paramResolver = mock(ReportParamResolver.class);
        var refresher = mock(EntityParamRefresher.class);
        var analysis = new Analysis(List.of(), List.of(), List.of(), java.util.Set.of("rate"));
        when(guard.guard(any(String.class), anySet(), any()))
                .thenReturn(GuardResult.allowed(analysis));
        when(paramResolver.resolve(any(), any(), any())).thenReturn(ResolvedParams.ok(Map.of("rate", 1.25)));

        var definition = new VisualQueryDefinition(3, "Product", "p", List.of(), List.of(), List.of(), List.of(),
                List.of(new VisualQueryDefinition.Expression("adjusted",
                        new VisualQueryExpression.Binary(VisualQueryExpression.Operator.MULTIPLY,
                                new VisualQueryExpression.FieldRef("amount"),
                                new VisualQueryExpression.ParameterRef("rate")))), null, null,
                List.of(new VisualQueryDefinition.Parameter("rate", "DECIMAL", true, null)));
        var query = new QueryDefinition("ignored", QuerySource.VISUAL,
                new VisualQueryDefinitionJsonCodec().write(definition), null, List.of());

        var result = new ReportQueryAssemblyService(guard, paramResolver, refresher)
                .assemble(query, null, Map.of());

        assertThat(result.jpql()).contains(":rate");
        assertThat(result.bindings()).containsEntry("rate", 1.25);
        verify(guard).guard(eq(result.jpql()), anySet(), any());
    }

    @Test
    void failsBeforeExecutionWhenVisualParameterIsMissing() {
        var guard = mock(ReportQueryGuard.class);
        var paramResolver = mock(ReportParamResolver.class);
        var refresher = mock(EntityParamRefresher.class);
        when(guard.guard(any(), anySet(), any())).thenReturn(GuardResult.allowed(
                new Analysis(List.of(), List.of(), List.of(), java.util.Set.of("rate"))));
        when(paramResolver.resolve(any(), any(), any())).thenReturn(ResolvedParams.ok(Map.of()));

        var definition = new VisualQueryDefinition(3, "Product", "p", List.of(), List.of(), List.of(), List.of(),
                List.of(new VisualQueryDefinition.Expression("adjusted", new VisualQueryExpression.ParameterRef("rate"))), null, null,
                List.of(new VisualQueryDefinition.Parameter("rate", "DECIMAL", true, null)));
        var query = new QueryDefinition("ignored", QuerySource.VISUAL,
                new VisualQueryDefinitionJsonCodec().write(definition), null, List.of());

        assertThatThrownBy(() -> new ReportQueryAssemblyService(guard, paramResolver, refresher)
                .assemble(query, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate");
    }
}
