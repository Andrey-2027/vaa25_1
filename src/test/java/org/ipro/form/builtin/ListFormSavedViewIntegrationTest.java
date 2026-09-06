package org.ipro.form.builtin;

import com.vaadin.flow.component.grid.Grid;
import org.ipro.crud.BaseEntity;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.GridViewState;
import org.ipro.form.spi.GridView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListFormSavedViewIntegrationTest {
    @Test
    void savedViewRestoresNestedUserFilterAndKeepsBottomBarAction() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestEntity.class).when(metadata).getEntityClass();
        when(metadata.getListColumnPaths()).thenReturn(List.of(ColumnPath.resolve(TestEntity.class, "id")));
        ListForm<TestEntity, Long> form = newForm(metadata);

        FilterNode fixed = node("id", FilterOperator.EQ, "1");
        FilterNode user = FilterGroup.or(node("id", FilterOperator.EQ, "2"), node("id", FilterOperator.EQ, "3"));
        GridView view = new GridView(null, "test", "saved", GridViewState.of(
            List.of(ColumnPath.resolve(TestEntity.class, "id")), TestEntity.class, fixed, user).toJson(), true, null);

        invokeApplyView(form, view);

        assertThat(form.getVisualFilter()).isNotNull();
        assertThat(form.getFilterGrid().visualFilterButton()).isNotNull();
        assertThat(form.getFilterGrid().visualFilter()).isNull();
    }

    private static void invokeApplyView(ListForm<TestEntity, Long> form, GridView view) {
        try {
            var method = ListForm.class.getDeclaredMethod("applyView", GridView.class);
            method.setAccessible(true);
            method.invoke(form, view);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static ListForm<TestEntity, Long> newForm(EntityMetadataInfo metadata) {
        return new ListForm<>(metadata, new org.ipro.filtergrid.FilterGrid<TestEntity>(TestEntity.class) {
            private final Grid<TestEntity> grid = new Grid<>(TestEntity.class, false);
            { add(grid); }
            @Override protected org.ipro.filtergrid.FilterSpecification<TestEntity> buildSpecification() {
                return (root, query, cb) -> cb.conjunction();
            }
            @Override public Grid<TestEntity> getGrid() { return grid; }
        });
    }

    private static FilterNode node(String path, FilterOperator operator, String value) {
        return FilterConditionNode.of(new FilterCondition(path, operator, value, null, FilterDataType.TEXT));
    }

    static class TestEntity extends BaseEntity {
    }
}
