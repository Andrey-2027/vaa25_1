package org.ipro.form.builtin;

import com.vaadin.flow.data.provider.Query;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.BaseService;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.GridViewState;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListPageReaderTest {
    @Test void datetimeComparisonsKeepTheMappedJpaTypeInsteadOfCastingToComparable() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(Row.class).when(metadata).getEntityClass();
        when(metadata.getListColumnPaths()).thenReturn(List.of(ColumnPath.resolve(Row.class, "period")));
        ListForm<Row, Long> form = new ListForm<>(metadata, mock(BaseService.class));
        var start = LocalDateTime.of(2026, 10, 9, 12, 34, 56, 123000000);
        var end = start.plusHours(1);
        var filter = FilterConditionNode.of(new org.ipro.filtergrid.filter.FilterCondition("period",
            org.ipro.filtergrid.filter.FilterOperator.BETWEEN, start.toString(), end.toString(),
            org.ipro.filtergrid.filter.FilterDataType.DATE));
        jakarta.persistence.criteria.Root<Row> root = mock(jakarta.persistence.criteria.Root.class);
        jakarta.persistence.criteria.Path<LocalDateTime> path = mock(jakarta.persistence.criteria.Path.class);
        doReturn(path).when(root).get("period");
        var cb = mock(jakarta.persistence.criteria.CriteriaBuilder.class);
        form.compileFilter(filter).toPredicate(root, null, cb);
        org.mockito.Mockito.verify(cb).between(path, start, end);
        org.mockito.Mockito.verify(path, org.mockito.Mockito.never()).as(org.mockito.ArgumentMatchers.any());
    }
    @Test void nativeTextAndDatetimeFiltersReachPageReaderAndSavedStateWithoutLoss() throws Exception {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(Row.class).when(metadata).getEntityClass();
        when(metadata.getListColumnPaths()).thenReturn(List.of(ColumnPath.resolve(Row.class, "name"), ColumnPath.resolve(Row.class, "period")));
        ListForm<Row, Long> form = new ListForm<>(metadata, mock(BaseService.class));
        var field = ListForm.class.getDeclaredField("activeFilters"); field.setAccessible(true);
        Map<String, ?> filters = (Map<String, ?>) field.get(form);
        TextFilter<?> text = (TextFilter<?>) filters.get("name");
        text.getModeSelect().setValue(TextFilter.FilterMode.ENDS_WITH);
        text.getTextField().setValue("М8");
        org.ipro.filtergrid.PeriodFilter<?, LocalDateTime> date = (org.ipro.filtergrid.PeriodFilter<?, LocalDateTime>) filters.get("period");
        date.getComponent().getFromField().setValue(LocalDateTime.of(2026, 10, 9, 12, 34, 56, 123000000));
        date.getComponent().applySelection();
        AtomicReference<FilterNode> seen = new AtomicReference<>();
        form.setPageReader((spec, tree, context, opaque, page, fetch) -> {
            seen.set(tree); return new PageImpl<>(List.of(), page, 12);
        });
        assertThat(form.getGrid().getDataProvider().size(new Query<>())).isEqualTo(12);
        assertThat(seen.get()).isInstanceOf(FilterGroup.class);
        String state = form.captureViewState().toJson();
        assertThat(state).contains("ENDS_WITH", "М8", "2026-10-09T12:34:56.123");
        assertThat(GridViewState.fromJson(state).userFilter()).isEqualTo(seen.get());
    }
    static class Row extends BaseEntity { String name; LocalDateTime period; }
}
