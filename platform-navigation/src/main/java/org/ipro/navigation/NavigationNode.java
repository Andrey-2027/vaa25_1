package org.ipro.navigation;

import java.util.List;
import java.util.Objects;

/** Complete metadata projection, including hidden and empty nodes, for the profile editor. */
public record NavigationNode(String id, Class<?> markerClass, String title, String icon,
                             int order, boolean defaultVisible,
                             List<NavigationItem> items, List<NavigationNode> children) {
    public NavigationNode {
        NavigationDefaults.requireId(id);
        Objects.requireNonNull(markerClass, "subsystem marker");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(icon, "icon");
        items = List.copyOf(items);
        children = List.copyOf(children);
    }

    public String key() { return "subsystem:" + id; }
}
