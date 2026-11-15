package org.ipro.navigation;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Caches the function index after the form registry is ready. */
public final class NavigationFunctionsProvider {
    private final NavigationFunctions source;
    private volatile Index index;

    public NavigationFunctionsProvider(NavigationFunctions source) {
        this.source = Objects.requireNonNull(source, "navigation functions");
    }

    private Index index() {
        Index result = index;
        if (result != null) return result;
        synchronized (this) {
            if (index == null) {
                var functions = List.copyOf(source.all());
                var byKey = new LinkedHashMap<String, NavigationFunction>();
                for (var function : functions) {
                    if (byKey.putIfAbsent(function.key(), function) != null) {
                        throw new IllegalArgumentException("Duplicate navigation function: " + function.key());
                    }
                }
                index = new Index(functions, Map.copyOf(byKey));
            }
            return index;
        }
    }

    public List<NavigationFunction> all() { return indexed().all(); }

    public Optional<NavigationFunction> find(String key) {
        if (key == null) return Optional.empty();
        return index().find(key).or(() -> source.dynamicFunctions().stream()
            .filter(function -> function.key().equals(key)).findFirst());
    }

    public void validate() { index(); }

    public NavigationFunctions indexed() {
        var fixed = index();
        var dynamic = source.dynamicFunctions();
        if (dynamic.isEmpty()) return fixed;
        var functions = new ArrayList<>(fixed.functions());
        var byKey = new LinkedHashMap<>(fixed.byKey());
        for (var function : dynamic) {
            if (byKey.putIfAbsent(function.key(), function) != null) {
                throw new IllegalArgumentException("Duplicate navigation function: " + function.key());
            }
            functions.add(function);
        }
        return new Index(List.copyOf(functions), Map.copyOf(byKey));
    }

    private record Index(List<NavigationFunction> functions, Map<String, NavigationFunction> byKey)
            implements NavigationFunctions {
        @Override public List<NavigationFunction> all() { return functions; }
        @Override public Optional<NavigationFunction> find(String key) {
            return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key));
        }
    }
}
