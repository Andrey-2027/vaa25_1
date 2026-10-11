package org.ipro.form.builtin;

import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.filtergrid.FieldFilter;
import org.ipro.filtergrid.FilterNodeProducer;
import org.ipro.filtergrid.FilterSpecification;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.util.JpaPathUtil;
import java.time.Duration;
import java.time.LocalDateTime;

/** Standard datetime controls and a lossless filter tree for millisecond periods. */
final class DateTimeRangeFilter<T> implements FieldFilter<T>, FilterNodeProducer<T> {
    private final DateTimePicker from = new DateTimePicker("От");
    private final DateTimePicker to = new DateTimePicker("До");
    private final VerticalLayout layout = new VerticalLayout(from, to);
    DateTimeRangeFilter() {
        from.setStep(Duration.ofMillis(1)); to.setStep(Duration.ofMillis(1));
        from.setWidthFull(); to.setWidthFull();
        layout.setPadding(false); layout.setSpacing(false); layout.setWidth("320px");
    }
    @Override public VerticalLayout getComponent() { return layout; }
    @Override public String getFilterTypeLabel() { return "Диапазон"; }
    @Override public FilterSpecification<T> createSpecification(String path) {
        LocalDateTime lower = from.getValue(), upper = to.getValue();
        if (from.isInvalid() || to.isInvalid()) throw new IllegalArgumentException("Введите корректные границы периода.");
        if (lower == null && upper == null) return null;
        return (root, query, cb) -> {
            var value = JpaPathUtil.<LocalDateTime>resolve(root, path);
            if (lower == null) return cb.lessThanOrEqualTo(value, upper);
            if (upper == null) return cb.greaterThanOrEqualTo(value, lower);
            return cb.between(value, lower, upper);
        };
    }
    @Override public FilterNode createFilterNode(String path) {
        if (from.getValue() == null && to.getValue() == null) return null;
        FilterNode lower = from.getValue() == null ? null : condition(path, FilterOperator.GE, from.getValue());
        FilterNode upper = to.getValue() == null ? null : condition(path, FilterOperator.LE, to.getValue());
        return lower == null ? upper : upper == null ? lower : FilterGroup.and(lower, upper);
    }
    private static FilterNode condition(String path, FilterOperator op, LocalDateTime value) {
        return FilterConditionNode.of(new FilterCondition(path, op, value.toString(), null, FilterDataType.DATE));
    }
}
