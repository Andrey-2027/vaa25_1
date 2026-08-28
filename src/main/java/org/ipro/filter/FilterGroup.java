package org.ipro.filter;

import java.util.List;

/** Группа условий, объединённых одним логическим оператором. */
public record FilterGroup(LogicalOperator operator, List<FilterNode> children) implements FilterNode {
    public FilterGroup {
        operator = operator == null ? LogicalOperator.AND : operator;
        children = children == null ? List.of() : List.copyOf(children);
    }

    public static FilterGroup and(FilterNode... children) {
        return new FilterGroup(LogicalOperator.AND, List.of(children));
    }

    public static FilterGroup or(FilterNode... children) {
        return new FilterGroup(LogicalOperator.OR, List.of(children));
    }
}
