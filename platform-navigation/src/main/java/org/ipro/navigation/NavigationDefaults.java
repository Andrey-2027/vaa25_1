package org.ipro.navigation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builder supplied to contributors by NavigationCatalog.build. IDs are explicit literals,
 * independent of class names, packages, titles and the current placement of an item.
 */
public final class NavigationDefaults {
    record Source(Class<?> subsystem, Class<?> entity) {}
    record Declaration(String id, boolean visible) {}

    private final Map<Class<?>, Declaration> subsystems = new HashMap<>();
    private final Map<Source, Declaration> items = new HashMap<>();
    private final Set<String> subsystemIds = new HashSet<>();
    private final Set<String> itemIds = new HashSet<>();
    private boolean frozen;

    NavigationDefaults() {}

    public NavigationDefaults subsystem(Class<?> marker, String id) {
        return subsystem(marker, id, true);
    }

    public NavigationDefaults subsystem(Class<?> marker, String id, boolean visible) {
        requireOpen();
        Objects.requireNonNull(marker, "subsystem marker");
        Declaration declaration = new Declaration(requireId(id), visible);
        if (subsystems.containsKey(marker)) {
            throw new IllegalArgumentException("Duplicate subsystem declaration: " + marker.getName());
        }
        if (!subsystemIds.add(id)) {
            throw new IllegalArgumentException("Duplicate subsystem ID: " + id);
        }
        subsystems.put(marker, declaration);
        return this;
    }

    public NavigationDefaults list(Class<?> subsystem, Class<?> entity, String id) {
        return list(subsystem, entity, id, true);
    }

    public NavigationDefaults list(Class<?> subsystem, Class<?> entity, String id, boolean visible) {
        requireOpen();
        Source source = new Source(Objects.requireNonNull(subsystem, "subsystem marker"),
            Objects.requireNonNull(entity, "entity class"));
        Declaration declaration = new Declaration(requireId(id), visible);
        if (items.containsKey(source)) {
            throw new IllegalArgumentException("Duplicate list declaration: " + source);
        }
        if (!itemIds.add(id)) {
            throw new IllegalArgumentException("Duplicate item ID: " + id);
        }
        items.put(source, declaration);
        return this;
    }

    static String requireId(String id) {
        Objects.requireNonNull(id, "navigation ID");
        if (!id.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("Navigation ID must be ASCII lower-kebab-case: " + id);
        }
        return id;
    }

    private void requireOpen() {
        if (frozen) {
            throw new IllegalStateException("Navigation declarations are frozen");
        }
    }

    void freeze() { frozen = true; }
    Map<Class<?>, Declaration> subsystems() { return Map.copyOf(subsystems); }
    Map<Source, Declaration> items() { return Map.copyOf(items); }
}
