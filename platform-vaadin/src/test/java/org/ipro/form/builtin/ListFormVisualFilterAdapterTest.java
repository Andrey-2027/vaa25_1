package org.ipro.form.builtin;

import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.filter.LogicalOperator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ListFormVisualFilterAdapterTest {
    @Test
    void composesFixedContextAndUserAndClearsOnlyUser() {
        FilterNode fixed = node("fixed");
        FilterNode context = node("context");
        FilterNode user = node("user");
        FilterNode[] applied = new FilterNode[1];
        ListFormVisualFilterAdapter<Object> adapter = new ListFormVisualFilterAdapter<>(
            root -> null, (specification, root) -> applied[0] = root);

        adapter.setFixed(fixed);
        adapter.setContext(context);
        adapter.setUser(user);
        assertThat(applied[0]).isNotNull();
        assertThat(adapter.user()).isSameAs(user);

        adapter.setUser(null);
        assertThat(applied[0]).isNotNull();
        assertThat(adapter.fixed()).isSameAs(fixed);
        assertThat(adapter.context()).isSameAs(context);
        assertThat(adapter.user()).isNull();
    }

    @Test
    void pruneTurnsEmptyTreeIntoNoFilter() {
        assertThat(ListFormVisualFilterAdapter.pruneEmptyGroups(null)).isNull();
        assertThat(ListFormVisualFilterAdapter.pruneEmptyGroups(
            new FilterGroup(LogicalOperator.OR, List.of()))).isNull();
        assertThat(ListFormVisualFilterAdapter.pruneEmptyGroups(new FilterGroup(LogicalOperator.AND,
            List.of(new FilterGroup(LogicalOperator.OR, List.of()))))).isNull();
    }

    @Test
    void pruneRemovesEmptyBranchAndKeepsRealCondition() {
        FilterNode condition = node("user");
        // Пустая группа под «ИЛИ» компилируется в CriteriaBuilder.or() без аргументов —
        // то есть в «ложь», поэтому «AND[пустой OR, условие]» показал бы ноль строк
        FilterNode tree = new FilterGroup(LogicalOperator.AND,
            List.of(new FilterGroup(LogicalOperator.OR, List.of()), condition));

        FilterNode pruned = ListFormVisualFilterAdapter.pruneEmptyGroups(tree);

        assertThat(pruned).isInstanceOf(FilterGroup.class);
        assertThat(((FilterGroup) pruned).children()).containsExactly(condition);
    }

    @Test
    void pruneKeepsGroupWithOneConditionAsTheUserGroupedIt() {
        FilterNode group = new FilterGroup(LogicalOperator.OR, List.of(node("user")));

        assertThat(ListFormVisualFilterAdapter.pruneEmptyGroups(group)).isEqualTo(group);
    }

    private static FilterNode node(String path) {
        return new FilterConditionNode(new FilterCondition(path, FilterOperator.IS_NULL,
            null, null, FilterDataType.TEXT));
    }
}
