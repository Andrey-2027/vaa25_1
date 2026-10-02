package org.ipro.rest.catalog;

import java.util.List;
import java.util.Map;

/** Maximum/default aliases and their resolved paths for one read operation. */
public record RestResourceProjection(RestReadOperation operation, List<String> maximumFields,
                                     List<String> defaultFields,
                                     Map<String, ResolvedRestField> fields) {
    public RestResourceProjection {
        maximumFields = List.copyOf(maximumFields);
        defaultFields = List.copyOf(defaultFields);
        fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
    }
}
