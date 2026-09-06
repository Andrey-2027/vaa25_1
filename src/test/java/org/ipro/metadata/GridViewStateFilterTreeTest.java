package org.ipro.metadata;

import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GridViewStateFilterTreeTest {
    @Test
    void serializesFixedAndNestedUserFilter() {
        FilterNode fixed = FilterConditionNode.of(new FilterCondition(
                "archived", FilterOperator.EQ, "false", null, FilterDataType.BOOLEAN));
        FilterNode user = FilterGroup.or(
                FilterConditionNode.of(new FilterCondition(
                        "name", FilterOperator.CONTAINS, "pump", null, FilterDataType.TEXT)),
                FilterConditionNode.of(new FilterCondition(
                        "code", FilterOperator.STARTS_WITH, "P", null, FilterDataType.TEXT)));
        GridViewState state = new GridViewState(List.of(), List.of(), fixed, user);

        GridViewState restored = GridViewState.fromJson(state.toJson());
        assertThat(restored.fixedFilter()).isEqualTo(fixed);
        assertThat(restored.userFilter()).isEqualTo(user);
        assertThat(restored.userFilterOrLegacy()).isEqualTo(user);
    }

    @Test
    void oldFlatFiltersBecomeUserAndGroup() {
        GridViewState state = new GridViewState(List.of(), List.of(
                new FilterSpec("name", "CONTAINS", "pump", null)));
        GridViewState restored = GridViewState.fromJson(state.toJson());
        assertThat(restored.fixedFilter()).isNull();
        assertThat(restored.userFilterOrLegacy()).isInstanceOf(org.ipro.filtergrid.filter.FilterGroup.class);
        assertThat(((org.ipro.filtergrid.filter.FilterGroup) restored.userFilterOrLegacy()).children()).hasSize(1);
    }
}
