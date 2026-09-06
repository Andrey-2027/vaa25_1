package org.ipro.form.builtin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListFormFilterGridIntegrationTest {
    @Test
    void listFormUsesFilterGridBottomBarInsteadOfLegacyTopPanel() {
        ListForm<TestEntity, Long> form = newForm();
        org.ipro.filtergrid.FilterGrid<TestEntity> grid = form.getFilterGrid();

        assertThat(grid.visualFilterButton()).isNotNull();
        assertThat(grid.visualFilterButton().getText()).isEqualTo("+ Условия");
        assertThat(grid.getChildren()).anyMatch(child -> child instanceof HorizontalLayout);
    }

    @Test
    void visualFilterButtonIsPlacedInFilterBarActions() {
        ListForm<TestEntity, Long> form = newForm();
        Button button = form.getFilterGrid().visualFilterButton();

        assertThat(button).isNotNull();
        Component parent = button.getParent().orElse(null);
        assertThat(parent).isInstanceOf(HorizontalLayout.class);
        assertThat(((HorizontalLayout) parent).getChildren()).contains(button);
    }

    private static ListForm<TestEntity, Long> newForm() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestEntity.class).when(metadata).getEntityClass();
        when(metadata.getListColumnPaths()).thenReturn(List.of(ColumnPath.resolve(TestEntity.class, "id")));

        return new ListForm<>(metadata,
            new org.ipro.filtergrid.FilterGrid<TestEntity>(TestEntity.class) {
                private final com.vaadin.flow.component.grid.Grid<TestEntity> grid =
                    new com.vaadin.flow.component.grid.Grid<>(TestEntity.class, false);
                {
                    add(grid);
                }

                @Override
                protected org.ipro.filtergrid.FilterSpecification<TestEntity> buildSpecification() {
                    return (root, query, cb) -> cb.conjunction();
                }

                @Override
                public com.vaadin.flow.component.grid.Grid<TestEntity> getGrid() {
                    return grid;
                }
            });
    }

    static class TestEntity extends BaseEntity {
    }
}
