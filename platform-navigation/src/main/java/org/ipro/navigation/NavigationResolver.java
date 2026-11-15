package org.ipro.navigation;

import java.util.Objects;
import java.util.function.Predicate;

/** Projects the menu using the current user's function availability. */
public final class NavigationResolver {
    public NavigationTree resolve(NavigationCatalog catalog, Predicate<NavigationItem> available) {
        Objects.requireNonNull(available, "availability predicate");
        return new NavigationTree(catalog.roots().stream()
            .map(node -> resolve(node, available)).flatMap(java.util.Optional::stream).toList());
    }

    private java.util.Optional<NavigationTree.Node> resolve(NavigationNode node, Predicate<NavigationItem> available) {
        var items = node.items().stream().filter(available).toList();
        var children = node.children().stream().map(child -> resolve(child, available))
            .flatMap(java.util.Optional::stream).toList();
        if (items.isEmpty() && children.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(new NavigationTree.Node(node.id(), node.areaKey(), node.title(), node.icon(),
            node.order(), items, children));
    }
}
