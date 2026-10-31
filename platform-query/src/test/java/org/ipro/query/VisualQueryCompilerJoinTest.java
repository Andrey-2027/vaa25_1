package org.ipro.query;

import org.ipro.query.*;
import org.ipro.query.param.*;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualQueryCompilerJoinTest {
    @Test
    void sameIndependentEntityCanBeJoinedUnderDifferentAliases() {
        var joins = List.of(
            new VisualQueryDefinition.Join(null, "Party", "origin", VisualQueryDefinition.JoinKind.INNER,
                new JoinCondition.Predicate("p.origin.id", JoinCondition.Operator.EQ, "origin.id")),
            new VisualQueryDefinition.Join(null, "Party", "destination", VisualQueryDefinition.JoinKind.INNER,
                new JoinCondition.Predicate("p.destination.id", JoinCondition.Operator.EQ, "destination.id")));
        var definition = new VisualQueryDefinition(3, "Transfer", "p",
            List.of(new VisualQueryDefinition.SelectField("id", "id")), joins, List.of(), List.of(), List.of(), null, null, List.of(), List.of());
        assertThat(VisualQueryCompiler.compile(definition).jpql())
            .contains("join Party origin on p.origin.id = origin.id", "join Party destination on p.destination.id = destination.id");
    }

    @Test
    void rejectsUnknownAliasEvenWhenOnFieldIsId() {
        var definition = new VisualQueryDefinition(3, "Product", "p",
            List.of(new VisualQueryDefinition.SelectField("id", "id")),
            List.of(new VisualQueryDefinition.Join(null, "Journal", "j", VisualQueryDefinition.JoinKind.INNER,
                new JoinCondition.Predicate("unknown.id", JoinCondition.Operator.EQ, "j.id"))),
            List.of(), List.of(), null);
        assertThatThrownBy(() -> VisualQueryCompiler.compile(definition))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ON");
    }
    @Test
    void definitionRoundTripSupportsJoin() throws Exception {
        var source = new VisualQueryDefinition(1, "PrdSpec", "s",
                List.of(new VisualQueryDefinition.SelectField("codeSpec", "code")),
                List.of(new VisualQueryDefinition.Join("s.journal", "j", VisualQueryDefinition.JoinKind.LEFT)),
                List.of(), List.of(), null);
        var restored = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(source), VisualQueryDefinition.class);
        assertThat(restored.joins()).containsExactlyElementsOf(source.joins());
    }

    @Test
    void rejectsDuplicateJoinAndAliasReuseWithoutCatalog() {
        var duplicate = new VisualQueryDefinition(1, "Product", "p",
                List.of(new VisualQueryDefinition.SelectField("code", "code")),
                List.of(new VisualQueryDefinition.Join("p.journal", "j", VisualQueryDefinition.JoinKind.INNER),
                        new VisualQueryDefinition.Join("p.journal", "j2", VisualQueryDefinition.JoinKind.LEFT)),
                List.of(), List.of(), null);
        assertThatThrownBy(() -> VisualQueryCompiler.compile(duplicate))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Повторный JOIN");

        var reused = new VisualQueryDefinition(1, "Product", "p",
                List.of(new VisualQueryDefinition.SelectField("code", "code")),
                List.of(new VisualQueryDefinition.Join("p.journal", "p", VisualQueryDefinition.JoinKind.INNER)),
                List.of(), List.of(), null);
        assertThatThrownBy(() -> VisualQueryCompiler.compile(reused))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Alias уже используется");
    }

    @Test
    void compilesInnerAndLeftJoinSyntax() {
        var definition = new VisualQueryDefinition(1, "Product", "p",
                List.of(new VisualQueryDefinition.SelectField("j.code", "journalCode")),
                List.of(new VisualQueryDefinition.Join("p.journal", "j", VisualQueryDefinition.JoinKind.LEFT)),
                List.of(), List.of(), null);
        var result = VisualQueryCompiler.compile(definition);
        assertThat(result.jpql()).isEqualTo("select j.code as journalCode from Product p left join p.journal j");
    }
}
