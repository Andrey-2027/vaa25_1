package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JoinConditionCompilerTest {
    @Test
    void compilesIndependentJoinWithFieldCondition() {
        var on = new JoinCondition.Group(JoinLogicalOperator.AND, List.of(
                new JoinCondition.Predicate("j.code", JoinCondition.Operator.EQ, "p.journalCode")));
        var definition = new VisualQueryDefinition(1, "PrdSpec", "p",
                List.of(new VisualQueryDefinition.SelectField("p.code", "code")),
                List.of(new VisualQueryDefinition.Join(null, "Journal", "j", VisualQueryDefinition.JoinKind.LEFT, on)),
                List.of(), List.of(), null);
        var result = VisualQueryCompiler.compile(definition);
        assertThat(result.jpql()).contains("left join Journal j on (j.code = p.journalCode)");
    }

    @Test
    void compilesAndOrOnConditionsWithoutRawJpql() {
        var on = new JoinCondition.Group(JoinLogicalOperator.OR, List.of(
                new JoinCondition.Predicate("j.code", JoinCondition.Operator.EQ, "p.journalCode"),
                new JoinCondition.Predicate("j.branch", JoinCondition.Operator.NE, "p.branch")));
        var definition = new VisualQueryDefinition(1, "PrdSpec", "p",
                List.of(new VisualQueryDefinition.SelectField("p.code", "code")),
                List.of(new VisualQueryDefinition.Join(null, "Journal", "j", VisualQueryDefinition.JoinKind.LEFT, on)),
                List.of(), List.of(), null);
        assertThat(VisualQueryCompiler.compile(definition).jpql())
                .contains("(j.code = p.journalCode OR j.branch <> p.branch)");
    }

    @Test
    void rejectsUnknownAliasInOn() {
        var on = new JoinCondition.Predicate("x.code", JoinCondition.Operator.EQ, "p.journalCode");
        var definition = new VisualQueryDefinition(1, "PrdSpec", "p",
                List.of(new VisualQueryDefinition.SelectField("p.code", "code")),
                List.of(new VisualQueryDefinition.Join(null, "Journal", "j", VisualQueryDefinition.JoinKind.LEFT, on)),
                List.of(), List.of(), null);
        assertThatThrownBy(() -> VisualQueryCompiler.compile(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Недопустимая ссылка");
    }
}
