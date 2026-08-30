package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualQueryParameterTest {
    @Test
    void parameterReferenceIsRenderedAsNamedBindingReference() {
        var definition = new VisualQueryDefinition(3, "Product", "p",
                List.of(), List.of(), List.of(), List.of(),
                List.of(new VisualQueryDefinition.Expression("adjusted",
                        new VisualQueryExpression.Binary(VisualQueryExpression.Operator.MULTIPLY,
                                new VisualQueryExpression.FieldRef("amount"),
                                new VisualQueryExpression.ParameterRef("rate")))),
                null, null,
                List.of(new VisualQueryDefinition.Parameter("rate", "DECIMAL", true, null)));
        var result = VisualQueryCompiler.compile(definition);
        assertThat(result.jpql()).contains(":rate");
    }

    @Test
    void undeclaredParameterIsRejected() {
        var definition = new VisualQueryDefinition(3, "Product", "p",
                List.of(), List.of(), List.of(), List.of(),
                List.of(new VisualQueryDefinition.Expression("x", new VisualQueryExpression.ParameterRef("missing"))),
                null, null, List.of());
        assertThatThrownBy(() -> VisualQueryCompiler.compile(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Параметр не объявлен");
    }

    @Test
    void parameterReferenceRoundTripsThroughJson() {
        var source = new VisualQueryDefinition(3, "Product", "p", List.of(), List.of(), List.of(), List.of(),
                List.of(new VisualQueryDefinition.Expression("x", new VisualQueryExpression.ParameterRef("rate"))), null, null,
                List.of(new VisualQueryDefinition.Parameter("rate", "DECIMAL", true, null)));
        var codec = new VisualQueryDefinitionJsonCodec();
        assertThat(codec.read(codec.write(source))).isEqualTo(source);
    }
}
