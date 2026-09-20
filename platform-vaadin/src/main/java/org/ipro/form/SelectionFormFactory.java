package org.ipro.form;

import java.util.Map;
import java.util.function.Consumer;

@FunctionalInterface
public interface SelectionFormFactory<T> {
    SelectionForm<T> create(Consumer<T> onSelect, Map<String, Object> filters);
}
