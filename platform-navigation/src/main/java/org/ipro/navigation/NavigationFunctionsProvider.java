package org.ipro.navigation;

import java.util.LinkedHashMap;
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

    public List<NavigationFunction> all() { return index().functions(); }

    public Optional<NavigationFunction> find(String key) {
        return index().find(key);
    }

    public void validate() { index(); }

    public NavigationFunctions indexed() {
        return index();
    }

    private record Index(List<NavigationFunction> functions, Map<String, NavigationFunction> byKey)
            implements NavigationFunctions {
        @Override public List<NavigationFunction> all() { return functions; }
        @Override public Optional<NavigationFunction> find(String key) {
            return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key));
        }
    }
}
