package org.ipro.navigation;

import java.util.Objects;

/** Opening the default list form. This describes navigation, not a form action or a permission. */
public record NavigationItem(String id, Class<?> entityClass, String title, String icon,
                             int order, boolean defaultVisible) {
    public NavigationItem {
        NavigationDefaults.requireId(id);
        Objects.requireNonNull(entityClass, "entity class");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(icon, "icon");
    }

    public String key() { return "item:" + id; }
}
