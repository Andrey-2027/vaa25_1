package org.ipro.navigation;

import java.util.Objects;

/** Derives stable navigation keys from marker class names. */
public final class NavigationEntityKeys {
    private NavigationEntityKeys() {}

    public static String derive(Class<?> type) {
        return NavigationIds.kebabCase(Objects.requireNonNull(type, "type").getSimpleName());
    }
}
