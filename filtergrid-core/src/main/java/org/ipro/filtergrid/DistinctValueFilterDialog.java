package org.ipro.filtergrid;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Minimal checkbox popup for selecting distinct column values. */
public final class DistinctValueFilterDialog<T, F> extends Dialog {
    private final CheckboxGroup<DistinctValue> values = new CheckboxGroup<>();
    private final com.vaadin.flow.component.checkbox.Checkbox includeNull =
            new com.vaadin.flow.component.checkbox.Checkbox("Не задано");

    public DistinctValueFilterDialog(String title, F field,
                                     DistinctValueProvider<T, F> provider,
                                     List<DistinctValue> selected,
                                     Consumer<List<DistinctValue>> applyConsumer) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(applyConsumer, "applyConsumer");
        setHeaderTitle(title);
        values.setLabel(title);
        values.setItemLabelGenerator(DistinctValue::label);
        List<DistinctValue> loaded = provider.fetch(field, 0, 500);
        List<DistinctValue> selectable = loaded.stream().filter(value -> !value.nullValue()).toList();
        values.setItems(selectable);
        if (selected != null) {
            values.setValue(selected.stream().filter(value -> !value.nullValue() && selectable.contains(value))
                    .collect(java.util.stream.Collectors.toSet()));
            includeNull.setValue(selected.stream().anyMatch(DistinctValue::nullValue));
        }
        boolean hasNull = loaded.stream().anyMatch(DistinctValue::nullValue);
        includeNull.setVisible(hasNull);
        Button apply = new Button("Применить", event -> {
            List<DistinctValue> selectedValues = new java.util.ArrayList<>(values.getSelectedItems());
            if (includeNull.getValue()) {
                loaded.stream().filter(DistinctValue::nullValue).findFirst().ifPresent(selectedValues::add);
            }
            applyConsumer.accept(selectedValues);
            close();
        });
        Button cancel = new Button("Отмена", event -> close());
        add(new VerticalLayout(values, includeNull, new HorizontalLayout(apply, cancel)));
    }

    public CheckboxGroup<DistinctValue> values() { return values; }
}
