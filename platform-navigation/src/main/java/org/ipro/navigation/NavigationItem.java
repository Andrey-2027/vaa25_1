package org.ipro.navigation;

import java.util.Objects;

/** Opening the default list form. This describes navigation, not a form action or a permission. */
public record NavigationItem(String id, Class<?> entityClass, String title, String icon,
                             int order, boolean defaultVisible, boolean additional) {
    public NavigationItem(String id, Class<?> entityClass, String title, String icon,
                          int order, boolean defaultVisible) {
        this(id, entityClass, title, icon, order, defaultVisible, false);
    }

    public NavigationItem {
        if (additional) {
            String[] segments = id.split("/", -1);
            if (segments.length != 2) {
                throw new IllegalArgumentException("Additional placement requires subsystem/entity: " + id);
            }
            NavigationIds.requireId(segments[0]);
            NavigationIds.requireId(segments[1]);
        } else {
            NavigationIds.requireId(id);
        }
        Objects.requireNonNull(entityClass, "entity class");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(icon, "icon");
    }

    public String key() { return "item:" + id; }
}
