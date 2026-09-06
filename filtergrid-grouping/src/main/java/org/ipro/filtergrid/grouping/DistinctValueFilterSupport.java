package org.ipro.filtergrid.grouping;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import org.ipro.filtergrid.DistinctValue;
import org.ipro.filtergrid.DistinctValueProvider;
import org.ipro.filtergrid.DistinctValueFilterDialog;
import org.ipro.filtergrid.ValueSetFilter;
import org.ipro.filtergrid.filter.FilterDataType;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Optional UI helper for wiring distinct-value popups to grid columns. */
public final class DistinctValueFilterSupport<T> {
    private final GroupValuesService<T> valuesService;
    private final Function<String, GroupField<T, ?>> fields;

    public DistinctValueFilterSupport(GroupValuesService<T> valuesService,
                                      Function<String, GroupField<T, ?>> fields) {
        this.valuesService = Objects.requireNonNull(valuesService, "valuesService");
        this.fields = Objects.requireNonNull(fields, "fields");
    }

    public Button createButton(String header, String fieldPath, FilterDataType dataType,
                               Runnable refresh) {
        Button button = new Button("⋮");
        button.addClickListener(event -> {
            GroupField<T, ?> field = fields.apply(fieldPath);
            List<DistinctValue> items = new GroupValuesDistinctValueProvider<>(valuesService, field)
                .fetch(field, 0, 500);
            ValueSetFilter<T> filter = new ValueSetFilter<>(dataType);
            filter.setItems(items.stream().map(DistinctValue::value).toList());
            DistinctValueFilterDialog<T, GroupField<T, ?>> dialog =
                new DistinctValueFilterDialog<>(header, field,
                    new GroupValuesDistinctValueProvider<>(valuesService, field),
                    List.of(), selected -> {
                        filter.setItems(selected.stream().map(DistinctValue::value).toList());
                        filter.setValue(selected.stream().map(DistinctValue::value).toList());
                        if (refresh != null) refresh.run();
                    });
            dialog.open();
        });
        button.getElement().setAttribute("aria-label", "Фильтр значений: " + header);
        return button;
    }
}
