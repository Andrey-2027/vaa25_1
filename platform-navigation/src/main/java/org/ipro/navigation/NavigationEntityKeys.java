package org.ipro.navigation;

import java.util.Objects;

/**
 * Application-side source of entity keys for menu items ({@code item:<key>}). The module does not
 * depend on Vaadin or on route publication: the application answers with the same key that its
 * form routes use, so a renamed class keeps its menu settings as long as the route key is kept.
 *
 * <p>Throwing while the key source is not ready (for example before the form registry is frozen)
 * is allowed: {@link NavigationCatalogProvider} does not cache a failed build.</p>
 */
@FunctionalInterface
public interface NavigationEntityKeys {
    String keyOf(Class<?> entityType);

    /** The derivation rule for a type without a published route: lower-kebab-case of the simple name. */
    static String derive(Class<?> entityType) {
        return NavigationIds.kebabCase(Objects.requireNonNull(entityType, "entity type").getSimpleName());
    }
}
