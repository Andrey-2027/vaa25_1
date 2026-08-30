package org.ipro.reportstudio.query;

import java.util.List;

/**
 * Текстовое представление AST вычисляемого поля — обратная операция к
 * {@link VisualQueryTextParser}: рендер можно скормить парсеру и получить
 * тот же AST. Грамматика совпадает с тем, что рендерит компилятор
 * ({@code renderExpression}): арифметика, функции allow-list, CASE.
 */
public final class VisualQueryExpressionText {

    private VisualQueryExpressionText() { }

    public static String render(VisualQueryExpression expression) {
        StringBuilder builder = new StringBuilder();
        renderTo(expression, builder);
        return builder.toString();
    }

    private static void renderTo(VisualQueryExpression expression, StringBuilder builder) {
        if (expression instanceof VisualQueryExpression.FieldRef field) {
            builder.append(field.path());
            return;
        }
        if (expression instanceof VisualQueryExpression.Literal literal) {
            renderLiteral(literal.value(), builder);
            return;
        }
        if (expression instanceof VisualQueryExpression.ParameterRef parameter) {
            builder.append(':').append(parameter.name());
            return;
        }
        if (expression instanceof VisualQueryExpression.Binary binary) {
            builder.append('(');
            renderTo(binary.left(), builder);
            builder.append(' ').append(binary.operator().symbol()).append(' ');
            renderTo(binary.right(), builder);
            builder.append(')');
            return;
        }
        if (expression instanceof VisualQueryExpression.FunctionCall function) {
            builder.append(function.name().toLowerCase()).append('(');
            for (int i = 0; i < function.arguments().size(); i++) {
                if (i > 0) builder.append(", ");
                renderTo(function.arguments().get(i), builder);
            }
            builder.append(')');
            return;
        }
        VisualQueryExpression.Case caseExpr = (VisualQueryExpression.Case) expression;
        builder.append("case");
        for (var branch : caseExpr.branches()) {
            builder.append(" when ");
            if (branch.condition() instanceof CaseCondition.Cmp cmp) {
                builder.append(cmp.leftPath()).append(' ').append(cmp.op().symbol()).append(' ')
                        .append(number(cmp.value()));
            } else {
                CaseCondition.Between between = (CaseCondition.Between) branch.condition();
                builder.append(between.leftPath()).append(" between ")
                        .append(number(between.min())).append(" and ").append(number(between.max()));
            }
            builder.append(" then ");
            renderTo(branch.result(), builder);
        }
        if (caseExpr.elseResult() != null) {
            builder.append(" else ");
            renderTo(caseExpr.elseResult(), builder);
        }
        builder.append(" end");
    }

    private static void renderLiteral(Object value, StringBuilder builder) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String s) {
            builder.append('\'').append(s.replace("'", "''")).append('\'');
        } else if (value instanceof Boolean b) {
            builder.append(b);
        } else if (value instanceof Number n) {
            builder.append(number(n.doubleValue()));
        } else {
            builder.append(value);
        }
    }

    private static String number(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
