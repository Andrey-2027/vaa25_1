package org.ipro.filter;

import java.util.Arrays;
import java.util.List;

/**
 * Человекочитаемое представление дерева условий, например:
 * <pre>Наименование Содержит 'олт' И (Тип Равно 'Узел' ИЛИ Тип Равно 'Материал')</pre>
 * Подписи полей берутся из {@link FilterFieldResolver} (label), операторы —
 * по словарю {@link FilterOperator}; вложенные группы оборачиваются в скобки.
 */
public final class FilterTreeText {
    private FilterTreeText() {
    }

    public static String render(FilterNode node, FilterFieldResolver resolver) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        render(node, resolver, sb);
        return sb.toString();
    }

    private static void render(FilterNode node, FilterFieldResolver resolver, StringBuilder sb) {
        if (node instanceof FilterConditionNode leaf) {
            sb.append(conditionText(leaf.condition(), resolver));
            return;
        }
        FilterGroup group = (FilterGroup) node;
        String join = group.operator() == LogicalOperator.OR ? " ИЛИ " : " И ";
        List<FilterNode> children = group.children();
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) sb.append(join);
            boolean nested = children.get(i) instanceof FilterGroup;
            if (nested) sb.append('(');
            render(children.get(i), resolver, sb);
            if (nested) sb.append(')');
        }
    }

    private static String conditionText(FilterCondition condition, FilterFieldResolver resolver) {
        String label = label(condition.path(), resolver);
        if (FilterCondition.requiresNoValue(condition.operator())) {
            return label + " " + operatorText(condition.operator());
        }
        if (condition.operator() == FilterOperator.BETWEEN) {
            return label + " " + operatorText(condition.operator())
                    + " '" + condition.value() + "' и '" + condition.valueTo() + "'";
        }
        if (condition.operator() == FilterOperator.IN) {
            String values = Arrays.stream(condition.value().split(",", -1))
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .map(v -> "'" + v + "'")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            return label + " " + operatorText(condition.operator()) + " (" + values + ")";
        }
        return label + " " + operatorText(condition.operator()) + " '" + condition.value() + "'";
    }

    private static String label(String path, FilterFieldResolver resolver) {
        if (resolver != null && path != null) {
            try {
                String label = resolver.resolve(path).label();
                if (label != null && !label.isBlank()) return label;
            } catch (RuntimeException ignored) {
            }
        }
        return path == null ? "" : path;
    }

    private static String operatorText(FilterOperator op) {
        return switch (op) {
            case EQ -> "Равно";
            case NE -> "Не равно";
            case CONTAINS -> "Содержит";
            case STARTS_WITH -> "Начинается с";
            case GT -> ">";
            case GE -> ">=";
            case LT -> "<";
            case LE -> "<=";
            case BETWEEN -> "Между";
            case IS_NULL -> "Пусто";
            case IS_NOT_NULL -> "Заполнено";
            case IN -> "В списке";
        };
    }
}
