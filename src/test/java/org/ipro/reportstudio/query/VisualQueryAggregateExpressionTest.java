package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualQueryAggregateExpressionTest {
    @Test
    void compilesGroupByAndCountRows() {
        var definition = new VisualQueryDefinition(2, "Order", "o",
                List.of(new VisualQueryDefinition.SelectField("status", "status")), List.of(),
                List.of("status"), List.of(new VisualQueryDefinition.Aggregate("COUNT_ROWS", "id", "rows")), List.of(), null);
        var compiled = VisualQueryCompiler.compile(definition);
        assertThat(compiled.jpql()).contains("count(o.id) as rows", "group by o.status");
    }

    @Test
    void compilesTypedArithmeticExpression() {
        var definition = new VisualQueryDefinition(2, "Order", "o",
                List.of(new VisualQueryDefinition.SelectField("amount", "amount")), List.of(), List.of(), List.of(),
                List.of(new VisualQueryDefinition.Expression("gross", new VisualQueryExpression.Binary(
                        VisualQueryExpression.Operator.MULTIPLY,
                        new VisualQueryExpression.FieldRef("amount"), new VisualQueryExpression.FieldRef("amount")))), null);
        assertThat(VisualQueryCompiler.compile(definition).jpql()).contains("(o.amount * o.amount) as gross");
    }

    @Test
    void rejectsUnsupportedFunctionAndRendersLiteralConstant() {
        var function = new VisualQueryDefinition(2, "Order", "o", List.of(), List.of(), List.of(), List.of(),
                List.of(new VisualQueryDefinition.Expression("x", new VisualQueryExpression.FunctionCall("evil", List.of(new VisualQueryExpression.FieldRef("amount"))))), null);
        assertThatThrownBy(() -> VisualQueryCompiler.compile(function)).isInstanceOf(IllegalArgumentException.class);
        // Литерал — типизированная Java-константа, рендерится как экранированный текст:
        var literal = new VisualQueryDefinition(2, "Order", "o", List.of(), List.of(), List.of(), List.of(),
                List.of(new VisualQueryDefinition.Expression("x", new VisualQueryExpression.Literal("raw jpql")),
                        new VisualQueryDefinition.Expression("q", new VisualQueryExpression.Literal("it's"))), null);
        String jpql = VisualQueryCompiler.compile(literal).jpql();
        assertThat(jpql).contains("'raw jpql' as x").contains("'it''s' as q");
    }
}
