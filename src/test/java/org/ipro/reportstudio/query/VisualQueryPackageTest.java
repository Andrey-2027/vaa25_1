package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualQueryPackageTest {
    @Test
    void acceptsEmptyPackage() {
        assertThatCode(() -> new VisualQueryPackage(List.of(), definition("Q6Product")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateCteNames() {
        assertThatThrownBy(() -> new VisualQueryPackage(List.of(
                new VisualQueryPackage.Cte("tmp1", definition("Q6Product")),
                new VisualQueryPackage.Cte("tmp1", definition("Q6Product"))),
                definition("Q6Product")))
                .hasMessageContaining("Повторное имя CTE");
    }

    @Test
    void rejectsInvalidCteName() {
        assertThatThrownBy(() -> new VisualQueryPackage(List.of(
                new VisualQueryPackage.Cte("1tmp", definition("Q6Product"))),
                definition("Q6Product")))
                .hasMessageContaining("Недопустимое имя");
    }

    @Test
    void acceptsPreviousCteInIndependentJoin() {
        VisualQueryDefinition main = new VisualQueryDefinition(
                VisualQueryDefinition.CURRENT_VERSION, "Q6Product", "p", List.of(
                new VisualQueryDefinition.SelectField("p.code", "code")),
                List.of(new VisualQueryDefinition.Join(null, "tmp1", "t",
                        VisualQueryDefinition.JoinKind.INNER)),
                List.of(), List.of(), List.of(), null);

        assertThatCode(() -> new VisualQueryPackage(List.of(
                new VisualQueryPackage.Cte("tmp1", definition("Q6Product"))), main))
                .doesNotThrowAnyException();
    }

    private static VisualQueryDefinition definition(String entityName) {
        return new VisualQueryDefinition(entityName, "p", List.of(
                new VisualQueryDefinition.SelectField("code", "code")));
    }
}
