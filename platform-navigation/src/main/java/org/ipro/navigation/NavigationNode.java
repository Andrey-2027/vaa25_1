package org.ipro.navigation;

import java.util.List;
import java.util.Objects;

/** Complete metadata projection, including empty sections. */
public record NavigationNode(String id, String areaKey, String title, String icon,
                             int order,
                             List<NavigationItem> items, List<NavigationNode> children) {
    public NavigationNode {
        NavigationIds.requireId(id);
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(icon, "icon");
        items = List.copyOf(items);
        children = List.copyOf(children);
    }

}
