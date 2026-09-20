package org.ipro.form.builtin;

import org.ipro.crud.BaseEntity;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListFormContextTest {

    @Test
    void openingContextSurvivesClearingInteractiveFilters() {
        ListForm<TestEntity, Long> form = newForm();

        form.setOpeningParameters(Map.of("sourceId", 42L));
        form.setOpeningContextFilter("id", 7L);
        form.setContextFilter("status", "OPEN");

        assertThat(form.getOpeningParameters()).containsEntry("sourceId", 42L);
        assertThat(form.getOpeningContextFilters()).containsEntry("id", 7L);
        assertThat(form.getContextFilterValues()).containsEntry("status", "OPEN");
        assertThat(form.getContextFilterValue("id")).isEqualTo(7L);

        form.clearContextFilter();

        assertThat(form.getContextFilterValues()).isEmpty();
        assertThat(form.getOpeningContextFilters()).containsEntry("id", 7L);
        assertThat(form.getContextFilterValue("id")).isEqualTo(7L);
        assertThat(form.getContextSnapshot().parameter("sourceId")).isEqualTo(42L);
    }

    @Test
    void clearingOneInteractiveFilterDoesNotClearAnother() {
        ListForm<TestEntity, Long> form = newForm();

        form.setContextFilter("status", "OPEN");
        form.setContextFilter("category", "A");
        form.clearContextFilter("status");

        assertThat(form.getContextFilterValues())
            .containsEntry("category", "A")
            .doesNotContainKey("status");
    }

    @Test
    void contextListenersSeeLatestSnapshot() {
        ListForm<TestEntity, Long> form = newForm();
        AtomicReference<org.ipro.form.registry.ListFormContext> observed = new AtomicReference<>();
        form.addContextChangeListener(observed::set);

        form.setContextFilter("status", "OPEN");

        assertThat(observed.get().contextFilters()).containsEntry("status", "OPEN");
    }

    @Test
    void contextSnapshotIsImmutable() {
        ListForm<TestEntity, Long> form = newForm();
        form.setOpeningParameters(Map.of("sourceId", 42L));
        form.setOpeningContextFilter("id", 7L);

        var snapshot = form.getContextSnapshot();

        assertThat(snapshot.openingParameters()).containsEntry("sourceId", 42L);
        assertThat(snapshot.openingFilters()).containsEntry("id", 7L);
        assertThat(snapshot.effectiveFilters()).containsEntry("id", 7L);
        assertThat(snapshot.parameter("sourceId")).isEqualTo(42L);
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
