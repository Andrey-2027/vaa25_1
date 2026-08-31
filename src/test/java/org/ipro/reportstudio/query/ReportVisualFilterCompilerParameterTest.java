package org.ipro.reportstudio.query;

import org.ipro.filter.FilterCondition;
import org.ipro.filter.FilterConditionNode;
import org.ipro.filter.FilterDataType;
import org.ipro.filter.FilterOperator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportVisualFilterCompilerParameterTest {
    @Test
    void sharedContextContinuesNumberingAcrossCompilations() {
        var resolver = new org.ipro.filter.FilterFieldResolver() {
            @Override
            public ResolvedFilterField resolve(String path) {
                return new ResolvedFilterField(path, path, String.class, FilterDataType.TEXT, true);
            }
        };
        var context = new ReportVisualFilterCompiler.ParameterContext();
        var first = new ReportVisualFilterCompiler(resolver, context)
                .compile(new FilterConditionNode(new FilterCondition(
                        "p.code", FilterOperator.EQ, "A", null, FilterDataType.TEXT)), Map.of());
        var second = new ReportVisualFilterCompiler(resolver, context)
                .compile(new FilterConditionNode(new FilterCondition(
                        "p.code", FilterOperator.EQ, "B", null, FilterDataType.TEXT)), Map.of());

        assertThat(first.predicate()).isEqualTo("p.code = :visualFilter_1");
        assertThat(second.predicate()).isEqualTo("p.code = :visualFilter_2");
        assertThat(first.bindings()).containsEntry("visualFilter_1", "A");
        assertThat(second.bindings()).containsEntry("visualFilter_2", "B");
        assertThat(context.size()).isEqualTo(2);
    }
}
