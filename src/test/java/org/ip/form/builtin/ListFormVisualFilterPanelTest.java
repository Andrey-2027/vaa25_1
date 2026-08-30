package org.ip.form.builtin;

import org.ipro.crud.BaseEntity;
import org.ipro.filter.FilterCondition;
import org.ipro.filter.FilterConditionNode;
import org.ipro.filter.FilterDataType;
import org.ipro.filter.FilterGroup;
import org.ipro.filter.FilterNode;
import org.ipro.filter.FilterOperator;
import org.ipro.metadata.EntityMetadataInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListFormVisualFilterPanelTest {
    @Test
    void panelIsCreatedWithoutLookupService() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestEntity.class).when(metadata).getEntityClass();
        when(metadata.getListColumnPaths()).thenReturn(List.of(
                org.ipro.metadata.ColumnPath.resolve(TestEntity.class, "id")));

        ListForm<TestEntity, Long> form = new ListForm<>(metadata,
                new org.ipro.filtergrid.FilterGrid<TestEntity>(TestEntity.class) {
                    private final com.vaadin.flow.component.grid.Grid<TestEntity> grid = new com.vaadin.flow.component.grid.Grid<>(TestEntity.class, false);
                    { add(grid); }
                    @Override protected org.ipro.filtergrid.FilterSpecification<TestEntity> buildSpecification() {
                        return (root, query, cb) -> cb.conjunction();
                    }
                    @Override public com.vaadin.flow.component.grid.Grid<TestEntity> getGrid() { return grid; }
                });

        assertThat(form.hasVisualFilterPanel()).isTrue();
        assertThat(form.getVisualFilterPanel()).isNotNull();
    }

    @Test
    void clearUserFilterDoesNotClearFixedOrContextNodes() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestEntity.class).when(metadata).getEntityClass();
        when(metadata.getListColumnPaths()).thenReturn(List.of(
                org.ipro.metadata.ColumnPath.resolve(TestEntity.class, "id")));

        ListForm<TestEntity, Long> form = new ListForm<>(metadata,
                new org.ipro.filtergrid.FilterGrid<TestEntity>(TestEntity.class) {
                    private final com.vaadin.flow.component.grid.Grid<TestEntity> grid = new com.vaadin.flow.component.grid.Grid<>(TestEntity.class, false);
                    { add(grid); }
                    @Override protected org.ipro.filtergrid.FilterSpecification<TestEntity> buildSpecification() {
                        return (root, query, cb) -> cb.conjunction();
                    }
                    @Override public com.vaadin.flow.component.grid.Grid<TestEntity> getGrid() { return grid; }
                });
        FilterNode fixed = node("id");
        FilterNode context = node("id");
        FilterNode user = node("id");

        form.setFixedFilter(fixed);
        form.setContextVisualFilter(context);
        form.setUserFilter(user);
        form.setUserFilter(null);

        assertThat(form.getVisualFilter()).isNotNull();
    }

    @Test
    void setUserFilterReflectsInVisualFilterPanel() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestEntity.class).when(metadata).getEntityClass();
        when(metadata.getListColumnPaths()).thenReturn(List.of(
                org.ipro.metadata.ColumnPath.resolve(TestEntity.class, "id")));

        ListForm<TestEntity, Long> form = new ListForm<>(metadata,
                new org.ipro.filtergrid.FilterGrid<TestEntity>(TestEntity.class) {
                    private final com.vaadin.flow.component.grid.Grid<TestEntity> grid = new com.vaadin.flow.component.grid.Grid<>(TestEntity.class, false);
                    { add(grid); }
                    @Override protected org.ipro.filtergrid.FilterSpecification<TestEntity> buildSpecification() {
                        return (root, query, cb) -> cb.conjunction();
                    }
                    @Override public com.vaadin.flow.component.grid.Grid<TestEntity> getGrid() { return grid; }
                });

        FilterNode filter = node("id");
        form.setUserFilter(filter);
        assertThat(form.getVisualFilterPanel().getEditor().getValue()).isEqualTo(filter);

        form.setUserFilter(null);
        assertThat(form.getVisualFilterPanel().getEditor().getValue()).isNull();
    }

    private static FilterNode node(String path) {
        return new FilterConditionNode(new FilterCondition(path, FilterOperator.IS_NULL,
                null, null, FilterDataType.TEXT));
    }

    static class TestEntity extends BaseEntity {
    }
}
