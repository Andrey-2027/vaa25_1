package org.ipro.form.registry;

import java.util.Map;

/** Необязательное ограничение списка выбора для конкретного ссылочного поля. */
public record SelectionFilter(Map<String, Object> values) {
    public SelectionFilter {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
