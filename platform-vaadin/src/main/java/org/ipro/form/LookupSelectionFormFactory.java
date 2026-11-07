package org.ipro.form;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A dependency-aware selection factory; paths belong to the opening field, not the target entity. */
@FunctionalInterface
public interface LookupSelectionFormFactory<T> extends SelectionFormFactory<T> {
    SelectionForm<T> create(Consumer<T> onSelect, Map<String, Object> filters, List<String> additionalPaths);

    @Override
    default SelectionForm<T> create(Consumer<T> onSelect, Map<String, Object> filters) {
        return create(onSelect, filters, List.of());
    }
}
