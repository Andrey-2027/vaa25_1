package org.ipro.form.builder.layout;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable related-field properties, independent of the visible field order. */
public record FieldRelations(Map<String, SelectionValue> selectionFilters, boolean clearInvalidSelection,
                             String fillFrom, boolean fillOnlyIfEmpty, boolean allowEmptySelectionSource) {
    public static final FieldRelations NONE = new FieldRelations(Map.of(), true, null, false, true);
    public FieldRelations {
        selectionFilters = Collections.unmodifiableMap(new LinkedHashMap<>(selectionFilters));
    }
}
