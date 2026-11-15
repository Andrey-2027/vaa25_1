package org.ipro.navigation;

import org.ipro.metadata.SubsystemNode;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.EntityMetadataInfo;

import java.util.ArrayList;
import java.util.Comparator;
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
 * change metadata, publish routes or grant data capabilities. Keys are derived, never declared:
 * a subsystem is {@code subsystem:<kebab-case of the marker simple name>}, the primary placement
 * of an entity is {@code item:<entity key>}, which stays stable when the entity moves.
 * An additional placement is {@code item:<subsystem ID>/<entity key>}.
 *
 * <p>Build it through {@link NavigationCatalogProvider}: the entity keys are known only after
 * the form registry is closed.</p>
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
        nodes.values().forEach(node -> node.commands().forEach(command -> allKeys.add(command.key())));
        this.keys = Set.copyOf(allKeys);
    }

    /**
     * Projects the registry. A repeated key, a key outside the grammar (ASCII lower-kebab-case) and
     * a key longer than the storage length (190) are configuration errors, reported together.
     */
    public static NavigationCatalog build(SubsystemRegistry registry, NavigationEntityKeys entityKeys) {
        Objects.requireNonNull(registry, "subsystem registry");
        Objects.requireNonNull(entityKeys, "entity keys");
        List<String> problems = new ArrayList<>();
        Set<Class<?>> visited = new HashSet<>();
        Map<String, String> claimed = new HashMap<>();
        List<NavigationNode> roots = new ArrayList<>();
        for (SubsystemNode root : registry.getRoots()) {
            roots.add(project(root, entityKeys, visited, claimed, problems));
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Navigation catalog is inconsistent: " + String.join("; ", problems));
        }
        return new NavigationCatalog(roots);
    }

    private static NavigationNode project(SubsystemNode source, NavigationEntityKeys entityKeys,
                                          Set<Class<?>> visited, Map<String, String> claimed,
                                          List<String> problems) {
        Class<?> marker = source.getMarkerClass();
        if (!visited.add(marker)) {
            throw new IllegalArgumentException("Repeated subsystem in source tree: " + marker.getName());
        }
        String id = NavigationIds.kebabCase(marker.getSimpleName());
        boolean validId = NavigationIds.isId(id);
        if (!validId) {
            problems.add("subsystem ID '" + id + "' of " + marker.getName() + " is not ASCII lower-kebab-case");
        }
        claim(claimed, "subsystem:" + id, marker.getName(), problems);
        List<NavigationItem> items = new ArrayList<>();
        collectItems(source.getOwnEntities(), "", false, entityKeys, items, claimed, problems);
        if (validId) {
            collectItems(source.getAdditionalEntities(), id + "/", true, entityKeys, items, claimed, problems);
        }
        items.sort(Comparator.comparingInt(NavigationItem::order)
            .thenComparing(item -> item.entityClass().getName()));
        List<NavigationNode> children = new ArrayList<>();
        for (SubsystemNode child : source.getChildren()) {
            children.add(project(child, entityKeys, visited, claimed, problems));
        }
        return new NavigationNode(validId ? id : "invalid", marker, source.getTitle(), source.getIcon(),
            source.getOrder(), true, items, children, collectCommands(source, id, entityKeys, claimed, problems));
    }

    private static List<NavigationCommand> collectCommands(SubsystemNode source, String id,
            NavigationEntityKeys entityKeys, Map<String, String> claimed, List<String> problems) {
        List<NavigationCommand> commands = new ArrayList<>();
        for (var declaration : source.getCommands()) {
            String entityKey = entityKeys.keyOf(declaration.entity());
            String action = declaration.action().strip();
            String variant = declaration.variant().strip();
            String key = "command:" + id + "/" + entityKey + "/" +
                (action.isEmpty() ? "list/" + variant : action);
            claim(claimed, key, source.getMarkerClass().getName(), problems);
            if (!NavigationIds.isId(entityKey) || !action.matches("[a-z][a-z0-9.-]*|")
                    || !variant.matches("[a-z][a-z0-9_-]*|")) {
                problems.add("invalid navigation command key: " + key);
            }
            commands.add(new NavigationCommand(key, declaration.entity(), action, variant,
                declaration.title(), declaration.icon(), declaration.order()));
        }
        return commands;
    }

    private static void collectItems(List<EntityMetadataInfo> entities, String prefix, boolean additional,
                                     NavigationEntityKeys entityKeys, List<NavigationItem> items,
                                     Map<String, String> claimed, List<String> problems) {
        for (var entity : entities) {
            Class<?> type = entity.getEntityClass();
            String key = Objects.requireNonNull(entityKeys.keyOf(type), "entity key of " + type.getName());
            claim(claimed, "item:" + prefix + key, type.getName(), problems);
            if (!NavigationIds.isId(key)) {
                problems.add("entity key '" + key + "' of " + type.getName() + " is not ASCII lower-kebab-case");
                continue;
            }
            items.add(new NavigationItem(prefix + key, type, entity.getListFormTitle(), entity.getIcon(),
                entity.getOrder(), true, additional));
        }
    }

    private static void claim(Map<String, String> claimed, String key, String owner, List<String> problems) {
        if (key.length() > NavigationIds.MAX_KEY_LENGTH) {
            problems.add("key '" + key + "' of " + owner + " is longer than " + NavigationIds.MAX_KEY_LENGTH);
        }
        String previous = claimed.putIfAbsent(key, owner);
        if (previous != null) {
            problems.add("key '" + key + "' is used by " + previous + " and " + owner);
        }
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
