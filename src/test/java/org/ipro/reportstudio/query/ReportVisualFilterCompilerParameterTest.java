package org.ipro.reportstudio.query;

import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.projection.ProjectionFilterCompiler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportVisualFilterCompilerParameterTest {
    @Test
    void sharedContextContinuesNumberingAcrossCompilations() {
        var resolver = new org.ipro.filtergrid.filter.FilterFieldResolver() {
            @Override
            public ResolvedFilterField resolve(String path) {
                return new ResolvedFilterField(path, path, String.class, FilterDataType.TEXT, true);
            }
        };
        var context = new ProjectionFilterCompiler.ParameterContext();
        var first = new ProjectionFilterCompiler(resolver, context)
                .compile(new FilterConditionNode(new FilterCondition(
                        "p.code", FilterOperator.EQ, "A", null, FilterDataType.TEXT)), Map.of());
        var second = new ProjectionFilterCompiler(resolver, context)
                .compile(new FilterConditionNode(new FilterCondition(
                        "p.code", FilterOperator.EQ, "B", null, FilterDataType.TEXT)), Map.of());

        assertThat(first.predicate()).isEqualTo("p.code = :visualFilter_1");
        assertThat(second.predicate()).isEqualTo("p.code = :visualFilter_2");
        assertThat(first.bindings()).containsEntry("visualFilter_1", "A");
        assertThat(second.bindings()).containsEntry("visualFilter_2", "B");
        assertThat(context.size()).isEqualTo(2);
    }
}
