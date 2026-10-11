package org.ipro.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Per-user resolved projection. Every nested list contains only visible entries. */
public record NavigationTree(List<Node> roots, Set<String> staleKeys) {
    public NavigationTree {
        roots = List.copyOf(roots);
        staleKeys = Set.copyOf(staleKeys);
    }

    public Optional<Node> findSubsystem(String id) {
        Objects.requireNonNull(id, "subsystem ID");
        return find(roots, id);
    }

    private static Optional<Node> find(List<Node> nodes, String id) {
        for (Node node : nodes) {
            if (node.id().equals(id)) return Optional.of(node);
            Optional<Node> child = find(node.children(), id);
            if (child.isPresent()) return child;
        }
        return Optional.empty();
    }

    public record Node(String id, Class<?> markerClass, String title, String icon, int order,
                       List<NavigationItem> items, List<Node> children) {
        public Node {
            NavigationDefaults.requireId(id);
            Objects.requireNonNull(markerClass, "subsystem marker");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(icon, "icon");
            items = List.copyOf(items);
            children = List.copyOf(children);
        }

        /** Own items first, then visible descendants; shared by parent pages and child pages. */
        public List<Group> groupsRecursive() {
            List<Group> groups = new ArrayList<>();
            collect(this, groups);
            return List.copyOf(groups);
        }

        private static void collect(Node node, List<Group> groups) {
            if (!node.items().isEmpty()) {
                groups.add(new Group(node.id(), node.title(), node.items()));
            }
            node.children().forEach(child -> collect(child, groups));
        }
    }

    public record Group(String subsystemId, String title, List<NavigationItem> items) {
        public Group {
            NavigationDefaults.requireId(subsystemId);
            Objects.requireNonNull(title, "title");
            items = List.copyOf(items);
        }
    }
}
