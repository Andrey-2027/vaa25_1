package org.ipro.navigation;

import org.ipro.metadata.SubsystemNode;
import org.ipro.metadata.SubsystemRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable projection of an already initialized SubsystemRegistry. Does not scan classes,
 * change metadata, publish routes or grant data capabilities. Build during startup;
 * missing/duplicate identities and declarations for unknown membership are configuration errors.
 */
public final class NavigationCatalog {
    private final List<NavigationNode> roots;
    private final Map<String, NavigationNode> nodes;
    private final Map<String, NavigationItem> items;
    private final Set<String> keys;

    private NavigationCatalog(List<NavigationNode> roots) {
        this.roots = List.copyOf(roots);
        Map<String, NavigationNode> nodeIndex = new HashMap<>();
        Map<String, NavigationItem> itemIndex = new HashMap<>();
        roots.forEach(node -> index(node, nodeIndex, itemIndex));
        this.nodes = Map.copyOf(nodeIndex);
        this.items = Map.copyOf(itemIndex);
        Set<String> allKeys = new TreeSet<>();
        nodes.values().forEach(node -> allKeys.add(node.key()));
        items.values().forEach(item -> allKeys.add(item.key()));
        this.keys = Set.copyOf(allKeys);
    }

    public static NavigationCatalog build(SubsystemRegistry registry,
                                         List<NavigationDefaultsContributor> contributors) {
        Objects.requireNonNull(registry, "subsystem registry");
        NavigationDefaults defaults = new NavigationDefaults();
        try {
            for (NavigationDefaultsContributor contributor : List.copyOf(contributors)) {
                contributor.contribute(defaults);
            }
        } finally {
            defaults.freeze();
        }
        Map<Class<?>, NavigationDefaults.Declaration> nodes = new HashMap<>(defaults.subsystems());
        Map<NavigationDefaults.Source, NavigationDefaults.Declaration> items = new HashMap<>(defaults.items());
        Set<Class<?>> visited = new HashSet<>();
        List<NavigationNode> roots = new ArrayList<>();
        for (SubsystemNode root : registry.getRoots()) {
            roots.add(project(root, nodes, items, visited));
        }
        if (!nodes.isEmpty() || !items.isEmpty()) {
            List<String> unknown = new ArrayList<>();
            nodes.keySet().forEach(type -> unknown.add("subsystem " + type.getName()));
            items.keySet().forEach(source -> unknown.add("list " + source));
            unknown.sort(String::compareTo);
            throw new IllegalArgumentException("Unknown navigation declarations: " + unknown);
        }
        return new NavigationCatalog(roots);
    }

    private static NavigationNode project(SubsystemNode source,
            Map<Class<?>, NavigationDefaults.Declaration> nodeDeclarations,
            Map<NavigationDefaults.Source, NavigationDefaults.Declaration> itemDeclarations,
            Set<Class<?>> visited) {
        Class<?> marker = source.getMarkerClass();
        if (!visited.add(marker)) {
            throw new IllegalArgumentException("Repeated subsystem in source tree: " + marker.getName());
        }
        NavigationDefaults.Declaration declaration = nodeDeclarations.remove(marker);
        if (declaration == null) {
            throw new IllegalArgumentException("Missing navigation ID for subsystem: " + marker.getName());
        }
        List<NavigationItem> items = new ArrayList<>();
        for (var entity : source.getOwnEntities()) {
            var key = new NavigationDefaults.Source(marker, entity.getEntityClass());
            var item = itemDeclarations.remove(key);
            if (item == null) {
                throw new IllegalArgumentException("Missing navigation ID for list: " + key);
            }
            items.add(new NavigationItem(item.id(), entity.getEntityClass(), entity.getListFormTitle(),
                entity.getIcon(), entity.getOrder(), item.visible()));
        }
        List<NavigationNode> children = new ArrayList<>();
        for (SubsystemNode child : source.getChildren()) {
            children.add(project(child, nodeDeclarations, itemDeclarations, visited));
        }
        return new NavigationNode(declaration.id(), marker, source.getTitle(), source.getIcon(),
            source.getOrder(), declaration.visible(), items, children);
    }

    private static void index(NavigationNode node, Map<String, NavigationNode> nodes,
                              Map<String, NavigationItem> items) {
        nodes.put(node.id(), node);
        node.items().forEach(item -> items.put(item.id(), item));
        node.children().forEach(child -> index(child, nodes, items));
    }

    public List<NavigationNode> roots() { return roots; }
    public Set<String> keys() { return keys; }
    public Optional<NavigationNode> findSubsystem(String id) { return Optional.ofNullable(nodes.get(id)); }
    public Optional<NavigationItem> findItem(String id) { return Optional.ofNullable(items.get(id)); }
}
