package org.ip.navigation;

import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.PublishedFormRoute;
import org.ipro.navigation.NavigationEntityKeys;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Menu keys follow the form route keys: the key of a published entity is its route key, so a class
 * renamed with an alias declaration keeps its menu settings. A type without a published route gets the
 * derived key (lower-kebab-case of the simple name) — its list still opens by class.
 *
 * <p>The route catalog is built after the form registry is closed, therefore the navigation catalog is
 * built lazily too ({@code NavigationCatalogProvider}).</p>
 */
@Configuration(proxyBeanMethods = false)
public class ApplicationNavigationEntityKeys {
    @Bean
    NavigationEntityKeys navigationEntityKeys(FormRouteCatalog routes) {
        return type -> routes.find(type).map(PublishedFormRoute::entityKey)
            .orElseGet(() -> NavigationEntityKeys.derive(type));
    }
}
