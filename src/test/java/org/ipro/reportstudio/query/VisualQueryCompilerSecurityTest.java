package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualQueryCompilerSecurityTest {
    @Test
    void rejectsUnsafeExpressionFieldPath() {
        var definition = new VisualQueryDefinition(2, "Product", "p", List.of(), List.of(), List.of(), List.of(),
                List.of(new VisualQueryDefinition.Expression("x", new VisualQueryExpression.FieldRef("p.code; delete"))), null);
        assertThatThrownBy(() -> VisualQueryCompiler.compile(definition)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsafeFunctionIdentifier() {
        var definition = new VisualQueryDefinition(2, "Product", "p", List.of(), List.of(), List.of(), List.of(),
                List.of(new VisualQueryDefinition.Expression("x", new VisualQueryExpression.FunctionCall("LOWER) from Evil", List.of(new VisualQueryExpression.FieldRef("code"))))), null);
        assertThatThrownBy(() -> VisualQueryCompiler.compile(definition)).isInstanceOf(IllegalArgumentException.class);
    }
}
