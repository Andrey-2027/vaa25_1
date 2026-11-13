package org.ipro.navigation;

import org.ipro.metadata.SubsystemRegistry;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Builds the catalog once, on first use. The entity keys come from the form routes, which are
 * available only after the form registry is closed, so the catalog cannot be built while beans
 * are created. A failed build is not cached: it is repeated (and fails again) on the next call.
 * The application starts {@link #validate()} on {@code ApplicationReadyEvent}.
 */
public final class NavigationCatalogProvider {
    private final Supplier<NavigationCatalog> source;
    private volatile NavigationCatalog catalog;

    private NavigationCatalogProvider(Supplier<NavigationCatalog> source) {
        this.source = Objects.requireNonNull(source, "catalog source");
    }

    /** Provider over a catalog that is already built (tests, manual composition). */
    public static NavigationCatalogProvider of(NavigationCatalog catalog) {
        Objects.requireNonNull(catalog, "navigation catalog");
        NavigationCatalogProvider provider = new NavigationCatalogProvider(() -> catalog);
        provider.catalog = catalog;
        return provider;
    }

    /** Provider that builds the catalog from the registry and the key source at the first call. */
    public static NavigationCatalogProvider deferred(Supplier<SubsystemRegistry> registry,
                                                    NavigationEntityKeys entityKeys) {
        Objects.requireNonNull(registry, "subsystem registry");
        Objects.requireNonNull(entityKeys, "entity keys");
        return new NavigationCatalogProvider(() -> NavigationCatalog.build(registry.get(), entityKeys));
    }

    public NavigationCatalog get() {
        NavigationCatalog current = catalog;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (catalog == null) {
                catalog = Objects.requireNonNull(source.get(), "navigation catalog");
            }
            return catalog;
        }
    }

    /** Startup check: build the catalog if it is not built yet; a configuration error stops startup. */
    public void validate() {
        get();
    }
}
