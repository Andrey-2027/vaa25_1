package org.ipro.filter;

/** Узел дерева фильтра: условие или вложенная логическая группа. */
public sealed interface FilterNode permits FilterConditionNode, FilterGroup {
}
