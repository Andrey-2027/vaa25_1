package org.ipro.reportstudio.query;

import org.ipro.reportstudio.dom.ReportQuerySource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.EntityParamRefresher;
import org.ipro.reportstudio.param.ReportParamResolver;
import org.ipro.reportstudio.param.ResolvedParams;
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
        var template = new ReportTemplate();
        template.setQuerySource(ReportQuerySource.VISUAL);
        template.setVisualQueryJson(new VisualQueryDefinitionJsonCodec().write(definition));
        template.setJpql("ignored");

        var result = new ReportQueryAssemblyService(guard, paramResolver, refresher)
                .assemble(template, null, Map.of());

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
        var template = new ReportTemplate();
        template.setQuerySource(ReportQuerySource.VISUAL);
        template.setVisualQueryJson(new VisualQueryDefinitionJsonCodec().write(definition));
        template.setJpql("ignored");

        assertThatThrownBy(() -> new ReportQueryAssemblyService(guard, paramResolver, refresher)
                .assemble(template, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate");
    }
}
