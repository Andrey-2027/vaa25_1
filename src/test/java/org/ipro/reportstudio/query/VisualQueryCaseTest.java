package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualQueryCaseTest {

    private VisualQueryDefinition.Expression caseExpression(VisualQueryExpression.Case c) {
        return new VisualQueryDefinition.Expression("label", c);
    }

    @Test
    void compilesCaseWhenWithCmpAndElse() {
        var expr = new VisualQueryExpression.Case(
                List.of(new CaseBranch(
                        new CaseCondition.Cmp("amount", CaseCondition.CmpOp.GT, 100),
                        new VisualQueryExpression.FieldRef("name"))),
                new VisualQueryExpression.ParameterRef("fallback"));
        var definition = new VisualQueryDefinition(3, "Order", "o",
                List.of(new VisualQueryDefinition.SelectField("amount", "amount")),
                List.of(), List.of(), List.of(),
                List.of(caseExpression(expr)), null, null,
                List.of(new VisualQueryDefinition.Parameter("fallback", "TEXT", false, "onbekend")),
                List.of());
        var compiled = VisualQueryCompiler.compile(definition);
        assertThat(compiled.jpql())
                .contains("case when o.amount > 100 then o.name else :fallback end as label");
    }

    @Test
    void compilesCaseWhenWithBetween() {
        var expr = new VisualQueryExpression.Case(
                List.of(new CaseBranch(
                        new CaseCondition.Between("amount", 10, 100),
                        new VisualQueryExpression.Literal("mid"))),
                null);
        var definition = new VisualQueryDefinition(3, "Order", "o",
                List.of(new VisualQueryDefinition.SelectField("amount", "amount")),
                List.of(), List.of(), List.of(),
                List.of(caseExpression(expr)), null, null, List.of(), List.of());
        var compiled = VisualQueryCompiler.compile(definition);
        assertThat(compiled.jpql()).contains("when o.amount between 10 and 100 then 'mid'");
    }

    @Test
    void caseConditionRejectsInjectionInPath() {
        assertThatThrownBy(() -> new CaseCondition.Cmp("amount; drop table x", CaseCondition.CmpOp.EQ, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaseCondition.Between("a.b; --", 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void caseRequiresAtLeastOneBranch() {
        assertThatThrownBy(() -> new VisualQueryExpression.Case(List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullifRequiresExactlyTwoArguments() {
        var one = new VisualQueryDefinition(3, "Order", "o", List.of(), List.of(), List.of(), List.of(),
                List.of(new VisualQueryDefinition.Expression("x", new VisualQueryExpression.FunctionCall(
                        "NULLIF", List.of(new VisualQueryExpression.FieldRef("amount"))))), null, null, List.of(), List.of());
        var three = new VisualQueryDefinition(3, "Order", "o", List.of(), List.of(), List.of(), List.of(),
                List.of(new VisualQueryDefinition.Expression("x", new VisualQueryExpression.FunctionCall(
                        "NULLIF", List.of(new VisualQueryExpression.FieldRef("amount"),
                                new VisualQueryExpression.FieldRef("amount"),
                                new VisualQueryExpression.FieldRef("amount"))))), null, null, List.of(), List.of());
        assertThatThrownBy(() -> VisualQueryCompiler.compile(one)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NULLIF");
        assertThatThrownBy(() -> VisualQueryCompiler.compile(three)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NULLIF");
    }

    @Test
    void dateFunctionsAreAllowed() {
        var definition = new VisualQueryDefinition(3, "Order", "o", List.of(), List.of(), List.of(), List.of(),
                List.of(new VisualQueryDefinition.Expression("year", new VisualQueryExpression.FunctionCall(
                        "YEAR", List.of(new VisualQueryExpression.FieldRef("createdAt"))))), null, null, List.of(), List.of());
        assertThat(VisualQueryCompiler.compile(definition).jpql()).contains("year(o.createdAt) as year");
    }

    @Test
    void caseJsonRoundTrips() {
        var codec = new VisualQueryDefinitionJsonCodec();
        var expr = new VisualQueryExpression.Case(
                List.of(
                        new CaseBranch(
                                new CaseCondition.Cmp("amount", CaseCondition.CmpOp.GE, 50),
                                new VisualQueryExpression.FieldRef("name")),
                        new CaseBranch(
                                new CaseCondition.Between("amount", 1, 49),
                                new VisualQueryExpression.ParameterRef("small"))),
                new VisualQueryExpression.Literal("empty"));
        var source = new VisualQueryDefinition(3, "Order", "o",
                List.of(new VisualQueryDefinition.SelectField("amount", "amount")),
                List.of(), List.of(), List.of(),
                List.of(caseExpression(expr)), null, null, List.of(), List.of());
        var restored = codec.read(codec.write(source));
        assertThat(restored).isEqualTo(source);
    }
}
