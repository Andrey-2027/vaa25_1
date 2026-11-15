package org.ipro.navigation;

import java.util.Objects;
import java.util.regex.Pattern;

/** A function that can be placed in the menu. Its identity is independent of placement. */
public record NavigationFunction(String key, Class<?> entityClass, Kind kind,
                                 String title, String icon, int order, String areaKey,
                                 String variant, String actionId) {
    public enum Kind { LIST, LIST_VARIANT, CREATE, ACTION, QUERY_LIST }
    private static final Pattern QUERY_KEY = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

    public NavigationFunction(String key, Class<?> entityClass, Kind kind,
                              String title, String icon, int order, String areaKey) {
        this(key, entityClass, kind, title, icon, order, areaKey, null, null);
    }

    public boolean command() {
        return kind == Kind.CREATE || kind == Kind.ACTION;
    }

    public NavigationFunction {
        Objects.requireNonNull(kind, "function kind");
        if (kind != Kind.QUERY_LIST) Objects.requireNonNull(entityClass, "entity class");
        Objects.requireNonNull(title, "function title");
        Objects.requireNonNull(icon, "function icon");
        String prefix = switch (kind) {
            case LIST, LIST_VARIANT -> "list:";
            case CREATE -> "create:";
            case ACTION -> "action:";
            case QUERY_LIST -> "query:";
        };
        String suffix = switch (kind) {
            case LIST_VARIANT -> ":" + requireQualifier(variant);
            case ACTION -> ":" + requireQualifier(actionId);
            case LIST, CREATE, QUERY_LIST -> "";
        };
        if (key == null || !key.startsWith(prefix) || !key.endsWith(suffix)
                || key.length() > NavigationIds.MAX_KEY_LENGTH || key.length() <= prefix.length() + suffix.length()
                || (kind == Kind.LIST_VARIANT) != (variant != null)
                || (kind == Kind.ACTION) != (actionId != null)) {
            throw new IllegalArgumentException("Invalid navigation function key: " + key);
        }
        String target = key.substring(prefix.length(), key.length() - suffix.length());
        if (kind == Kind.QUERY_LIST) {
            if (target.length() > 100 || !QUERY_KEY.matcher(target).matches()) {
                throw new IllegalArgumentException("Invalid query list key: " + target);
            }
        } else {
            NavigationIds.requireId(target);
        }
        if (areaKey != null) NavigationIds.requireId(areaKey);
    }
    private static String requireQualifier(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid navigation function qualifier: " + value);
        }
        return value;
    }
}
