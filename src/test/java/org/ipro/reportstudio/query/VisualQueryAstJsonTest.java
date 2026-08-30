package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class VisualQueryAstJsonTest {
    @Test
    void typedExpressionRoundTripsThroughCodec() {
        var expression = new VisualQueryDefinition.Expression("total",
                new VisualQueryExpression.Binary(
                        VisualQueryExpression.Operator.ADD,
                        new VisualQueryExpression.FieldRef("amount"),
                        new VisualQueryExpression.FunctionCall("ABS",
                                List.of(new VisualQueryExpression.FieldRef("discount")))));
        var source = new VisualQueryDefinition(2, "Product", "p", List.of(), List.of(), List.of(), List.of(),
                List.of(expression), null);
        var codec = new VisualQueryDefinitionJsonCodec();
        assertThat(codec.read(codec.write(source))).isEqualTo(source);
    }
}
