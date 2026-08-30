package org.ipro.reportstudio.query;

import org.ipro.filter.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportVisualFilterCompilerParameterTest {
    private final FilterFieldResolver resolver = new FilterFieldResolver() {
        private final ResolvedFilterField amount = new ResolvedFilterField("amount", "Amount", java.math.BigDecimal.class, FilterDataType.NUMBER, true);
        @Override public ResolvedFilterField resolve(String path) { if ("amount".equals(path)) return amount; throw new IllegalArgumentException(path); }
        @Override public List<ResolvedFilterField> fields() { return List.of(amount); }
    };

    @Test
    void compilesParameterForBetween() {
        var node = new FilterConditionNode(new FilterCondition("amount", FilterOperator.BETWEEN, ":from", ":to", FilterDataType.NUMBER));
        var result = new ReportVisualFilterCompiler(resolver).compile(node, Map.of("from", 1, "to", 5));
        assertThat(result.predicate()).contains(":visualFilterParam_from", ":visualFilterParam_to");
        assertThat(result.bindings()).containsEntry("visualFilterParam_from", 1).containsEntry("visualFilterParam_to", 5);
    }

    @Test
    void compilesParameterForInAndRequiresCollection() {
        var node = new FilterConditionNode(new FilterCondition("amount", FilterOperator.IN, ":values", null, FilterDataType.NUMBER));
        var result = new ReportVisualFilterCompiler(resolver).compile(node, Map.of("values", List.of(1, 2)));
        assertThat(result.bindings()).containsEntry("visualFilterParam_values", List.of(1, 2));
        assertThatThrownBy(() -> new ReportVisualFilterCompiler(resolver).compile(node, Map.of("values", 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("списком");
    }

    @Test
    void rejectsMissingFilterParameter() {
        var node = new FilterConditionNode(new FilterCondition("amount", FilterOperator.GT, ":minimum", null, FilterDataType.NUMBER));
        assertThatThrownBy(() -> new ReportVisualFilterCompiler(resolver).compile(node, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minimum");
    }
}
