package org.ipro.navigation;

import java.util.Objects;
import java.util.regex.Pattern;

/** Grammar and derivation of navigation identities. Keys are derived, never declared by hand. */
final class NavigationIds {
    static final int MAX_KEY_LENGTH = 190;
    private static final Pattern GRAMMAR = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    private NavigationIds() {}

    static boolean isId(String id) {
        return id != null && id.length() <= MAX_KEY_LENGTH && GRAMMAR.matcher(id).matches();
    }

    static String requireId(String id) {
        Objects.requireNonNull(id, "navigation ID");
        if (!isId(id)) {
            throw new IllegalArgumentException("Navigation ID must be ASCII lower-kebab-case: " + id);
        }
        return id;
    }

    /**
     * ASCII lower-kebab-case of a simple class name: a boundary precedes an upper-case letter after
     * a lower-case letter or digit, and an upper-case letter that opens a word inside an acronym
     * ({@code XMLParser} becomes {@code xml-parser}). Same rule as the form route key.
     */
    static String kebabCase(String simpleName) {
        StringBuilder result = new StringBuilder(simpleName.length() + 4);
        for (int i = 0; i < simpleName.length(); i++) {
            char current = simpleName.charAt(i);
            if (Character.isUpperCase(current)) {
                char previous = i == 0 ? 0 : simpleName.charAt(i - 1);
                boolean boundary = i > 0 && (!Character.isUpperCase(previous)
                    || i + 1 < simpleName.length() && Character.isLowerCase(simpleName.charAt(i + 1)));
                if (boundary) {
                    result.append('-');
                }
                result.append(Character.toLowerCase(current));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }
}
