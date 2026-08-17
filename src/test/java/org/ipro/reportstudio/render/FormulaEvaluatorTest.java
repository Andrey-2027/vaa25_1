package org.ipro.reportstudio.render;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormulaEvaluatorTest {

    @Test
    void evaluatesOperatorPrecedence() {
        assertThat(evaluate("2 + 3 * 4")).isEqualByComparingTo("14");
        assertThat(evaluate("10 - 2 - 3")).isEqualByComparingTo("5");
        assertThat(evaluate("20 / 2 / 5")).isEqualByComparingTo("2");
    }

    @Test
    void parenthesesOverridePrecedence() {
        assertThat(evaluate("(2 + 3) * 4")).isEqualByComparingTo("20");
        assertThat(evaluate("((1 + 2)) * 3")).isEqualByComparingTo("9");
    }

    @Test
    void aliasesAreResolvedFromRow() {
        BigDecimal result = evaluate("({qty} * {price}) + 6",
                Map.of("qty", "4", "price", "5"));
        assertThat(result).isEqualByComparingTo("26");
    }

    @Test
    void decimalsSupported() {
        assertThat(evaluate("1.5 + 2.25")).isEqualByComparingTo("3.75");
        assertThat(evaluate("0.1 * 0.2")).isEqualByComparingTo("0.02");
    }

    @Test
    void divisionByZeroGivesNullNotError() {
        assertThat(evaluate("1 / 0")).isNull();
        assertThat(evaluate("({qty} / {zero})", Map.of("qty", "2", "zero", "0"))).isNull();
    }

    @Test
    void nullAliasValuePropagatesNull() {
        BigDecimal result = FormulaEvaluator.evaluate("{a} + {b}",
                alias -> "a".equals(alias) ? BigDecimal.ONE : null);
        assertThat(result).isNull();
    }

    @Test
    void unknownAliasRejected() {
        assertThatThrownBy(() -> FormulaEvaluator.evaluate("{ghost} + 1",
                alias -> {
                    throw new IllegalArgumentException("Нет колонки «" + alias + "»");
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void validateAcceptsWellFormedFormula() {
        FormulaEvaluator.validate("({qty} * {price}) + 6");
        FormulaEvaluator.validate("42");
        FormulaEvaluator.validate("1.5");
    }

    @Test
    void validateRejectsBrokenGrammar() {
        assertThatThrownBy(() -> FormulaEvaluator.validate("2 +"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormulaEvaluator.validate("(* 2)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormulaEvaluator.validate("{qty"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormulaEvaluator.validate(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormulaEvaluator.validate("2 + 2 3"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aliasesOfReportsReferencedColumns() {
        assertThat(FormulaEvaluator.aliasesOf("({qty} * {price}) + {tax}"))
                .containsExactly("price", "qty", "tax");
        assertThat(FormulaEvaluator.aliasesOf("42")).isEmpty();
    }

    @Test
    void toBigDecimalConvertsCommonTypes() {
        assertThat(FormulaEvaluator.toBigDecimal(4)).isEqualByComparingTo("4");
        assertThat(FormulaEvaluator.toBigDecimal(4.5)).isEqualByComparingTo("4.5");
        assertThat(FormulaEvaluator.toBigDecimal(new BigDecimal("7.25"))).isEqualByComparingTo("7.25");
        assertThat(FormulaEvaluator.toBigDecimal("3.5")).isEqualByComparingTo("3.5");
        assertThat(FormulaEvaluator.toBigDecimal(null)).isNull();
        assertThat(FormulaEvaluator.toBigDecimal("не число")).isNull();
    }

    private static BigDecimal evaluate(String formula) {
        return FormulaEvaluator.evaluate(formula, alias -> {
            throw new IllegalArgumentException("Нет колонки «" + alias + "»");
        });
    }

    private static BigDecimal evaluate(String formula, Map<String, String> values) {
        return FormulaEvaluator.evaluate(formula, alias -> {
            String raw = values.get(alias);
            if (raw == null) {
                throw new IllegalArgumentException("Нет колонки «" + alias + "»");
            }
            return new BigDecimal(raw);
        });
    }
}