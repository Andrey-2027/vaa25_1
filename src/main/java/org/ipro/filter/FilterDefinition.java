package org.ipro.filter;

import java.util.List;

/** Набор условий, применяемых к данным как единое декларативное правило. */
public record FilterDefinition(LogicalOperator operator, List<FilterCondition> conditions) {
    public FilterDefinition {
        operator = operator == null ? LogicalOperator.AND : operator;
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    public static FilterDefinition empty() {
        return new FilterDefinition(LogicalOperator.AND, List.of());
    }
}
