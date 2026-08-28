package org.ipro.filter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilterCompositionTest {
    private static FilterCondition condition(String path, String value) {
        return new FilterCondition(path, FilterOperator.EQ, value, null, FilterDataType.TEXT);
    }

    @Test
    void keepsUserOrGroupInsideAndComposition() {
        FilterNode fixed = FilterConditionNode.of(condition("archived", "false"));
        FilterNode context = FilterConditionNode.of(condition("warehouse", "1"));
        FilterNode user = FilterGroup.or(
                FilterConditionNode.of(condition("name", "pump")),
                FilterConditionNode.of(condition("code", "P")));

        FilterNode root = new FilterComposition(fixed, context, user).root();
        assertThat(root).isInstanceOf(FilterGroup.class);
        FilterGroup and = (FilterGroup) root;
        assertThat(and.operator()).isEqualTo(LogicalOperator.AND);
        assertThat(and.children()).hasSize(3);
        assertThat(and.children().get(2)).isEqualTo(user);
        assertThat(((FilterGroup) and.children().get(2)).operator()).isEqualTo(LogicalOperator.OR);
    }

    @Test
    void emptyLayersProduceNoRoot() {
        assertThat(new FilterComposition(null, null, null).root()).isNull();
    }
}
