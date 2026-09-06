package org.ipro.filtergrid;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** A column filter whose selected values are compiled as an IN condition. */
public final class ValueSetFilter<T> implements FieldFilter<T>, FilterNodeProducer<T> {
    private final CheckboxGroup<Object> component = new CheckboxGroup<>();
    private final FilterDataType dataType;
    private boolean includeNull;

    public ValueSetFilter(FilterDataType dataType) { this.dataType = Objects.requireNonNull(dataType, "dataType"); }
    public void setItems(List<?> items) { component.setItems(items == null ? List.of() : items); }
    public void setValue(List<?> selected) { component.setValue(selected == null ? java.util.Set.of() : selected.stream().collect(Collectors.toSet())); }
    public List<Object> getValue() { return component.getSelectedItems().stream().toList(); }
    public boolean isIncludeNull() { return includeNull; }
    public void setIncludeNull(boolean includeNull) { this.includeNull = includeNull; }
    @Override public Component getComponent() { return component; }
    @Override public FilterSpecification<T> createSpecification(String fieldPath) {
        List<Object> selected = getValue();
        if (selected.isEmpty() && !includeNull) return null;
        return (root, query, cb) -> {
            var path = org.ipro.filtergrid.util.JpaPathUtil.resolve(root, fieldPath);
            var in = cb.in(path);
            selected.forEach(in::value);
            return includeNull ? cb.or(in, cb.isNull(path)) : in;
        };
    }
    @Override public FilterNode createFilterNode(String fieldPath) {
        List<Object> selected = getValue();
        if (selected.isEmpty() && !includeNull) return null;
        String encoded = selected.stream().map(String::valueOf).collect(Collectors.joining(","));
        if (includeNull) encoded = encoded.isEmpty() ? "NULL" : encoded + ",NULL";
        return FilterConditionNode.of(new FilterCondition(fieldPath, FilterOperator.IN, encoded, null, dataType));
    }
    @Override public boolean hasActiveValue() { return !getValue().isEmpty() || includeNull; }
    @Override public String getFilterTypeLabel() { return "IN"; }
    @Override public String getDisplayValue() {
        String result = getValue().stream().map(String::valueOf).collect(Collectors.joining(", "));
        return includeNull ? (result.isEmpty() ? "Не задано" : result + ", Не задано") : result;
    }
}
