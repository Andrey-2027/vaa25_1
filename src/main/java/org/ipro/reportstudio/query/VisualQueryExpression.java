package org.ipro.reportstudio.query;

import java.util.List;
import java.util.Objects;

/** Типобезопасное AST вычисляемого поля визуального запроса. */
public sealed interface VisualQueryExpression
        permits VisualQueryExpression.FieldRef, VisualQueryExpression.Literal,
        VisualQueryExpression.ParameterRef, VisualQueryExpression.Binary,
        VisualQueryExpression.FunctionCall, VisualQueryExpression.Case {
    record FieldRef(String path) implements VisualQueryExpression {
        public FieldRef { if (path == null || path.isBlank()) throw new IllegalArgumentException("Поле выражения обязательно"); }
    }
    record Literal(Object value) implements VisualQueryExpression { }
    record ParameterRef(String name) implements VisualQueryExpression {
        public ParameterRef { new VisualQueryParameter(name); }
    }
    record Binary(Operator operator, VisualQueryExpression left,
                  VisualQueryExpression right) implements VisualQueryExpression {
        public Binary {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }
    record FunctionCall(String name, List<VisualQueryExpression> arguments) implements VisualQueryExpression {
        public FunctionCall {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Имя функции обязательно");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        }
    }
    enum Operator {
        ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/");
        private final String symbol;
        Operator(String symbol) { this.symbol = symbol; }
        public String symbol() { return symbol; }
    }

    /** Безопасный CASE WHEN: список веток + необязательный ELSE. */
    record Case(List<CaseBranch> branches, VisualQueryExpression elseResult) implements VisualQueryExpression {
        public Case {
            branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
            if (branches.isEmpty()) throw new IllegalArgumentException("CASE должен иметь хотя бы одну ветку");
        }
    }
}
