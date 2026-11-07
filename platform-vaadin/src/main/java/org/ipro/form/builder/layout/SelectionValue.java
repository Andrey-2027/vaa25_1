package org.ipro.form.builder.layout;

/** Closed vocabulary of a selection parameter: a constant, the edited entity, or a current input value. */
public sealed interface SelectionValue {
    record Constant(Object value) implements SelectionValue {
        public Constant {
            if (value instanceof java.util.Collection<?> values) value = java.util.List.copyOf(values);
        }
    }
    record Self() implements SelectionValue { }
    record FieldValue(String path) implements SelectionValue {
        public FieldValue {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("Blank source field path");
        }
    }
}
