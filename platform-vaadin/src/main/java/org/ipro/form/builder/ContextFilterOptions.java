package org.ipro.form.builder;

import java.util.Map;

/** Immutable per-field policy; legacy declarations keep equality semantics. */
public record ContextFilterOptions(Operation operation, Condition condition,
        String group, boolean labelsAbove, Map<String, Object> selectionFilters) {
    public enum Operation { EQ, AUTO, PERIOD }
    @FunctionalInterface public interface Condition extends java.io.Serializable {
        org.springframework.data.jpa.domain.Specification<?> create(Object value);
    }
    public ContextFilterOptions {
        selectionFilters = selectionFilters == null ? Map.of() : Map.copyOf(selectionFilters);
    }
    public static ContextFilterOptions legacy() { return new ContextFilterOptions(Operation.EQ, null, null, false, Map.of()); }
}
