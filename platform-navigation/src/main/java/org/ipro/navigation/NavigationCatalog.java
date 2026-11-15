package org.ipro.navigation;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable projection of menu sections and entries. Profile keys identify placements
 * ({@code section:<id>} and {@code entry:<id>}) independently of their parent and function.
 */
public final class NavigationCatalog {
    private final List<NavigationNode> roots;
    private final Map<String, NavigationNode> nodes;
    private final Map<String, NavigationItem> items;

    private NavigationCatalog(List<NavigationNode> roots) {
        this.roots = List.copyOf(roots);
        Map<String, NavigationNode> nodeIndex = new HashMap<>();
        Map<String, NavigationItem> itemIndex = new HashMap<>();
        roots.forEach(node -> index(node, nodeIndex, itemIndex));
        this.nodes = Map.copyOf(nodeIndex);
        this.items = Map.copyOf(itemIndex);
    }

    public static NavigationCatalog build(NavigationStructure structure, NavigationFunctions functions) {
        var children = new HashMap<String, List<NavigationStructure.Section>>();
        var entries = new HashMap<String, List<NavigationStructure.Entry>>();
        structure.sections().forEach(section ->
            children.computeIfAbsent(section.parentId(), key -> new ArrayList<>()).add(section));
        structure.entries().forEach(entry ->
            entries.computeIfAbsent(entry.sectionId(), key -> new ArrayList<>()).add(entry));
        var roots = children.getOrDefault(null, List.of()).stream()
            .sorted(java.util.Comparator.comparingInt(NavigationStructure.Section::position)
                .thenComparing(NavigationStructure.Section::id))
            .map(section -> project(section, children, entries, functions)).toList();
        return new NavigationCatalog(roots);
    }

    private static NavigationNode project(NavigationStructure.Section section,
            Map<String, List<NavigationStructure.Section>> children,
            Map<String, List<NavigationStructure.Entry>> entries, NavigationFunctions functions) {
        var items = entries.getOrDefault(section.id(), List.of()).stream()
            .sorted(java.util.Comparator.comparingInt(NavigationStructure.Entry::position)
                .thenComparing(NavigationStructure.Entry::id))
            .flatMap(entry -> functions.find(entry.functionKey()).stream().map(function ->
                new NavigationItem(entry.id(), function,
                    entry.title() == null ? function.title() : entry.title(), function.icon(), entry.position(), entry.importance())))
            .toList();
        var nested = children.getOrDefault(section.id(), List.of()).stream()
            .sorted(java.util.Comparator.comparingInt(NavigationStructure.Section::position)
                .thenComparing(NavigationStructure.Section::id))
            .map(child -> project(child, children, entries, functions)).toList();
        return new NavigationNode(section.id(), section.areaKey(), section.title(), section.icon(),
            section.position(), items, nested);
    }

    private static void index(NavigationNode node, Map<String, NavigationNode> nodes,
                              Map<String, NavigationItem> items) {
        nodes.put(node.id(), node);
        node.items().forEach(item -> items.put(item.id(), item));
        node.children().forEach(child -> index(child, nodes, items));
    }

    public List<NavigationNode> roots() { return roots; }
    public Optional<NavigationNode> findSubsystem(String id) { return Optional.ofNullable(nodes.get(id)); }
    public Optional<NavigationItem> findItem(String id) { return Optional.ofNullable(items.get(id)); }
}
