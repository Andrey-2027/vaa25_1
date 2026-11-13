package org.ipro.navigation;

import java.util.Objects;
import java.util.function.Supplier;

/** Supplies a fresh catalog from persisted menu structure. */
public final class NavigationCatalogProvider {
    private final Supplier<NavigationCatalog> source;

    private NavigationCatalogProvider(Supplier<NavigationCatalog> source) {
        this.source = source;
    }

    /** Provider over a catalog that is already built (tests, manual composition). */
    public static NavigationCatalogProvider of(NavigationCatalog catalog) {
        Objects.requireNonNull(catalog, "navigation catalog");
        return new NavigationCatalogProvider(() -> catalog);
    }

    public static NavigationCatalogProvider stored(NavigationStructureReader structure, NavigationFunctionsProvider functions) {
        return new NavigationCatalogProvider(() -> NavigationCatalog.build(structure.current(), functions.indexed()));
    }

    public NavigationCatalog get() {
        return source.get();
    }
}
