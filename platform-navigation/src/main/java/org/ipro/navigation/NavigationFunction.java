package org.ipro.navigation;

import java.util.Objects;

/** A function that can be placed in the menu. Its identity is independent of placement. */
public record NavigationFunction(String key, Class<?> entityClass, Kind kind,
                                 String title, String icon, int order, String areaKey,
                                 String variant, String actionId) {
    public enum Kind { LIST, LIST_VARIANT, CREATE, ACTION }

    public NavigationFunction(String key, Class<?> entityClass, Kind kind,
                              String title, String icon, int order, String areaKey) {
        this(key, entityClass, kind, title, icon, order, areaKey, null, null);
    }

    public boolean command() {
        return kind == Kind.CREATE || kind == Kind.ACTION;
    }

    public NavigationFunction {
        Objects.requireNonNull(entityClass, "entity class");
        Objects.requireNonNull(kind, "function kind");
        Objects.requireNonNull(title, "function title");
        Objects.requireNonNull(icon, "function icon");
        String prefix = switch (kind) {
            case LIST, LIST_VARIANT -> "list:";
            case CREATE -> "create:";
            case ACTION -> "action:";
        };
        String suffix = switch (kind) {
            case LIST_VARIANT -> ":" + requireQualifier(variant);
            case ACTION -> ":" + requireQualifier(actionId);
            case LIST, CREATE -> "";
        };
        if (key == null || !key.startsWith(prefix) || !key.endsWith(suffix)
                || key.length() > NavigationIds.MAX_KEY_LENGTH || key.length() <= prefix.length() + suffix.length()
                || (kind == Kind.LIST_VARIANT) != (variant != null)
                || (kind == Kind.ACTION) != (actionId != null)) {
            throw new IllegalArgumentException("Invalid navigation function key: " + key);
        }
        NavigationIds.requireId(key.substring(prefix.length(), key.length() - suffix.length()));
        if (areaKey != null) NavigationIds.requireId(areaKey);
    }
    private static String requireQualifier(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid navigation function qualifier: " + value);
        }
        return value;
    }
}
