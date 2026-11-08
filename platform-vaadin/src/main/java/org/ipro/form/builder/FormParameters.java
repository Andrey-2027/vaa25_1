package org.ipro.form.builder;

import java.util.*;

/** Declared business parameters, separate from coordinator-owned opening data. */
public final class FormParameters {
    private static final Set<String> OPENING_DATA = Set.of(
        "presetEntity", "presetRows", "initialValues", "contextFilters", "readOnly", "fields", "seedFilter", "seedFilters");
    private final Map<String, FormParameter<?>> declarations = new LinkedHashMap<>();
    private Map<String, Object> values = Map.of();

    public void add(FormParameter<?> parameter) {
        Objects.requireNonNull(parameter);
        if (OPENING_DATA.contains(parameter.name())) throw new IllegalArgumentException("Reserved opening key: " + parameter.name());
        if (declarations.putIfAbsent(parameter.name(), parameter) != null)
            throw new IllegalArgumentException("Duplicate form parameter: " + parameter.name());
    }

    public void bind(Map<String, Object> supplied) {
        var result = new LinkedHashMap<String, Object>();
        if (supplied != null) supplied.forEach((name, value) -> {
            var parameter = declarations.get(name);
            if (parameter != null) result.put(name, parameter.read(value));
            else if (!OPENING_DATA.contains(name)) throw new IllegalArgumentException("Unknown form parameter: " + name);
        });
        values = Collections.unmodifiableMap(result);
    }

    public <V> V get(FormParameter<V> parameter) {
        if (declarations.get(parameter.name()) != parameter)
            throw new IllegalArgumentException("Undeclared form parameter: " + parameter.name());
        return parameter.read(values.get(parameter.name()));
    }

    /** Typed values travel through the existing navigator's compatibility Map channel. */
    public static Map<String, Object> values(FormParameter.Value<?>... supplied) {
        var result = new LinkedHashMap<String, Object>();
        for (var value : supplied) {
            String name = value.parameter().name();
            if (result.containsKey(name)) throw new IllegalArgumentException("Duplicate form parameter: " + name);
            result.put(name, value.value());
        }
        return Collections.unmodifiableMap(result);
    }
}
