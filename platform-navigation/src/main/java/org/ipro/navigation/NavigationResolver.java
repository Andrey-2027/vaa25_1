package org.ipro.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Stateless visibility composition. The application supplies its existing read gate;
 * SHOW cannot override a hidden ancestor or a denied read. Predicate failures propagate.
 */
public final class NavigationResolver {
    public NavigationTree resolve(NavigationCatalog catalog, Map<String, NavigationVisibility> overrides,
                                  Predicate<Class<?>> canRead) {
        return resolve(catalog, overrides, canRead, command -> false);
    }

    public NavigationTree resolve(NavigationCatalog catalog, Map<String, NavigationVisibility> overrides,
                                  Predicate<Class<?>> canRead, Predicate<NavigationCommand> available) {
        Objects.requireNonNull(available, "command availability");
        Objects.requireNonNull(catalog, "navigation catalog");
        Objects.requireNonNull(canRead, "read predicate");
        Map<String, NavigationVisibility> snapshot = Map.copyOf(overrides);
        Set<String> staleKeys = new TreeSet<>(snapshot.keySet());
        staleKeys.removeAll(catalog.keys());
        List<NavigationTree.Node> roots = new ArrayList<>();
        for (NavigationNode node : catalog.roots()) {
            NavigationTree.Node resolved = resolve(node, snapshot, canRead, available);
            if (resolved != null) roots.add(resolved);
        }
        return new NavigationTree(roots, staleKeys);
    }

    private NavigationTree.Node resolve(NavigationNode node, Map<String, NavigationVisibility> overrides,
                                        Predicate<Class<?>> canRead, Predicate<NavigationCommand> available) {
        if (!overrides.getOrDefault(node.key(), NavigationVisibility.DEFAULT).resolve(node.defaultVisible())) {
            return null;
        }
        List<NavigationItem> items = node.items().stream()
            .filter(item -> overrides.getOrDefault(item.key(), NavigationVisibility.DEFAULT)
                .resolve(item.defaultVisible()))
            .filter(item -> canRead.test(item.entityClass()))
            .toList();
        List<NavigationTree.Node> children = new ArrayList<>();
        for (NavigationNode child : node.children()) {
            NavigationTree.Node resolved = resolve(child, overrides, canRead, available);
            if (resolved != null) children.add(resolved);
        }
        var commands = node.commands().stream()
            .filter(command -> overrides.getOrDefault(command.key(), NavigationVisibility.DEFAULT)
                .resolve(command.defaultVisible()))
            .filter(available)
            .toList();
        if (items.isEmpty() && children.isEmpty() && commands.isEmpty()) return null;
        return new NavigationTree.Node(node.id(), node.markerClass(), node.title(), node.icon(),
            node.order(), items, children, commands);
    }
}
