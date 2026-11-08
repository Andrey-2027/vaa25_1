package org.ipro.form.builder;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** A form-owned opening parameter, including validation at the legacy Map boundary. */
public final class FormParameter<T> {
    private final String name;
    private final T defaultValue;
    private final Function<Object, T> reader;

    private FormParameter(String name, T defaultValue, Function<Object, T> reader) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Blank form parameter");
        this.name = name;
        this.defaultValue = defaultValue;
        this.reader = reader;
    }

    public static <V> FormParameter<V> of(String name, Class<V> type, V defaultValue) {
        Objects.requireNonNull(type);
        if (defaultValue != null) type.cast(defaultValue);
        return new FormParameter<>(name, defaultValue, value -> {
            if (!type.isInstance(value)) throw new IllegalArgumentException("Expected " + type.getName());
            return type.cast(value);
        });
    }

    /** Immutable list of section classes; each member must belong to the declared domain. */
    public static FormParameter<List<Class<?>>> sections(String name, Class<?>... allowed) {
        var domain = List.of(allowed);
        return new FormParameter<>(name, List.of(), value -> {
            if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected a list of section classes");
            for (Object member : list) {
                if (!(member instanceof Class<?> type) || !domain.contains(type))
                    throw new IllegalArgumentException("Unknown section: " + member);
            }
            return list.stream().<Class<?>>map(member -> (Class<?>) member).toList();
        });
    }

    public String name() { return name; }
    T read(Object value) {
        if (value == null) return defaultValue;
        try { return reader.apply(value); }
        catch (RuntimeException failure) {
            throw new IllegalArgumentException("Form parameter '" + name + "': " + failure.getMessage(), failure);
        }
    }
    public Value<T> with(T value) { return new Value<>(this, read(value)); }
    public record Value<V>(FormParameter<V> parameter, V value) {
        public Value { Objects.requireNonNull(parameter); value = parameter.read(value); }
    }
}
