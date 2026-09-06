package org.ipro.form.builtin;

import com.vaadin.flow.component.grid.Grid;
import org.ipro.crud.BaseEntity;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.grouping.GroupField;
import org.ipro.filtergrid.grouping.GroupValuesService;
import org.ipro.filtergrid.grouping.GroupableJpaFilterGrid;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListFormGroupingFilterIntegrationTest {
    @Test
    void composedVisualFilterIsForwardedToGroupableGrid() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestEntity.class).when(metadata).getEntityClass();
        when(metadata.getListColumnPaths()).thenReturn(List.of(ColumnPath.resolve(TestEntity.class, "id")));
        @SuppressWarnings("unchecked")
        GroupValuesService<TestEntity> groups = mock(GroupValuesService.class);
        GroupableJpaFilterGrid<TestEntity> grid = new GroupableJpaFilterGrid<>(
            TestEntity.class, (spec, pageable) -> org.springframework.data.domain.Page.empty(pageable), groups);
        ListForm<TestEntity, Long> form = new ListForm<>(metadata, grid);

        FilterNode filter = FilterConditionNode.of(new FilterCondition(
            "id", FilterOperator.EQ, "1", null, FilterDataType.TEXT));
        form.setUserFilter(filter);

        assertThat(form.getVisualFilter()).isNotNull();
        assertThat(grid).isNotNull();
    }

    static class TestEntity extends BaseEntity {
    }
}
