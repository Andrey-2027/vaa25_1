package org.ipro.reportstudio.query;

import org.ipro.filter.FilterCondition;
import org.ipro.filter.FilterConditionNode;
import org.ipro.filter.FilterDataType;
import org.ipro.filter.FilterOperator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisualQueryPackageCompilerTest {
    @Test
    void compilesCtesAndMainQueryInOrderWithSharedBindings() {
        var first = new VisualQueryDefinition(
                VisualQueryDefinition.CURRENT_VERSION, "Q6Product", "p", List.of(
                new VisualQueryDefinition.SelectField("code", "code")),
                List.of(), List.of(), List.of(), List.of(), null,
                new FilterConditionNode(new FilterCondition(
                        "p.code", FilterOperator.EQ, "A", null, FilterDataType.TEXT)),
                List.of(), List.of());
        var second = new VisualQueryDefinition(
                VisualQueryDefinition.CURRENT_VERSION, "tmp1", "t", List.of(
                new VisualQueryDefinition.SelectField("code", "code")),
                List.of(), List.of(), List.of(), List.of(), null,
                new FilterConditionNode(new FilterCondition(
                        "t.code", FilterOperator.EQ, "B", null, FilterDataType.TEXT)),
                List.of(), List.of());
        var main = new VisualQueryDefinition("Q6Product", "p", List.of(
                new VisualQueryDefinition.SelectField("code", "code")));

        var result = VisualQueryCompiler.compile(new VisualQueryPackage(List.of(
                new VisualQueryPackage.Cte("tmp1", first),
                new VisualQueryPackage.Cte("tmp2", new VisualQueryPackage.CteSource("t", "tmp1"), second)), main), null);

        assertThat(result.jpql()).isEqualTo(
                "with tmp1 as (select p.code as code from Q6Product p where p.code = :visualFilter_1), "
                        + "tmp2 as (select t.code as code from tmp1 t where t.code = :visualFilter_2) "
                        + "select p.code as code from Q6Product p");
        assertThat(result.bindings()).containsEntry("visualFilter_1", "A")
                .containsEntry("visualFilter_2", "B");
    }
}
