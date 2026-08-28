package org.ipro.filter;

import java.util.Objects;

/** Лист дерева условий. */
public record FilterConditionNode(FilterCondition condition) implements FilterNode {
    public FilterConditionNode {
        Objects.requireNonNull(condition, "condition");
    }

    public static FilterConditionNode of(FilterCondition condition) {
        return new FilterConditionNode(condition);
    }
}
