package org.ipro.form.builtin;

import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.junit.jupiter.api.Test;

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

    private static FilterNode node(String path) {
        return new FilterConditionNode(new FilterCondition(path, FilterOperator.IS_NULL,
            null, null, FilterDataType.TEXT));
    }
}
