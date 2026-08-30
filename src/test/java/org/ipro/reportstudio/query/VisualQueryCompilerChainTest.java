package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualQueryCompilerChainTest {
    @Test
    void compilesJoinChainFromPreviousAliasWithoutCatalog() {
        var definition = new VisualQueryDefinition(1, "PrdSpec", "p",
                List.of(new VisualQueryDefinition.SelectField("g.name", "groupName")),
                List.of(
                        new VisualQueryDefinition.Join("p", "nomenclature", "n", VisualQueryDefinition.JoinKind.LEFT),
                        new VisualQueryDefinition.Join("n", "groupNom", "g", VisualQueryDefinition.JoinKind.LEFT)),
                List.of(), List.of(), null);

        var result = VisualQueryCompiler.compile(definition);

        assertThat(result.jpql()).contains("left join p.nomenclature n")
                .contains("left join n.groupNom g")
                .contains("g.name as groupName");
    }

    @Test
    void rejectsJoinWithUnknownParentAlias() {
        var definition = new VisualQueryDefinition(1, "PrdSpec", "p",
                List.of(new VisualQueryDefinition.SelectField("codeSpec", "code")),
                List.of(new VisualQueryDefinition.Join("missing", "nomenclature", "n", VisualQueryDefinition.JoinKind.LEFT)),
                List.of(), List.of(), null);

        assertThatThrownBy(() -> VisualQueryCompiler.compile(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Неизвестный родительский alias");
    }
}
