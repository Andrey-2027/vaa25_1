package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisualQueryPackageVirtualCatalogTest {
    @Test
    void infersVirtualColumnTypes() {
        var definition = new VisualQueryDefinition(
                VisualQueryDefinition.CURRENT_VERSION, "Q6Product", "p", List.of(
                new VisualQueryDefinition.SelectField("code", "code")),
                List.of(), List.of(),
                List.of(
                        new VisualQueryDefinition.Aggregate("COUNT_ROWS", "p.id", "rows"),
                        new VisualQueryDefinition.Aggregate("MIN", "p.code", "firstCode")),
                List.of(
                        new VisualQueryDefinition.Expression("constant", new VisualQueryExpression.Literal("x")),
                        new VisualQueryDefinition.Expression("calculated", new VisualQueryExpression.Binary(
                                VisualQueryExpression.Operator.ADD,
                                new VisualQueryExpression.Literal(1), new VisualQueryExpression.Literal(2)))),
                null);

        var result = VisualQueryCompiler.compile(new VisualQueryPackage(List.of(
                new VisualQueryPackage.Cte("tmp1", definition)), definition), null);

        assertThat(result.fields()).extracting(org.ipro.reportstudio.data.QueryField::name)
                .containsExactly("code");
    }
}
