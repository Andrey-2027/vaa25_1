package org.ipro.navigation;

import java.util.Objects;

/** A menu placement and the function it invokes. */
public record NavigationItem(String id, NavigationFunction function, String title, String icon,
                             int order, NavigationImportance importance) {
    public NavigationItem {
        NavigationIds.requireId(id);
        Objects.requireNonNull(function, "navigation function");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(importance, "importance");
    }

    public Class<?> entityClass() { return function.entityClass(); }
}
