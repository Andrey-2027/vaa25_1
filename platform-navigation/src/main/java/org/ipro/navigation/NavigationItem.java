package org.ipro.navigation;

import java.util.Objects;

/** A menu placement and the function it invokes. */
public record NavigationItem(String id, NavigationFunction function, String title, String icon,
                             int order, boolean defaultVisible) {
    public NavigationItem(String id, Class<?> entityClass, String title, String icon,
                          int order, boolean defaultVisible) {
        this(id, new NavigationFunction("list:" + id, entityClass, NavigationFunction.Kind.LIST,
            title, icon, order, null), title, icon, order, defaultVisible);
    }

    public NavigationItem {
        NavigationIds.requireId(id);
        Objects.requireNonNull(function, "navigation function");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(icon, "icon");
    }

    public Class<?> entityClass() { return function.entityClass(); }

    public String key() { return "entry:" + id; }
}
