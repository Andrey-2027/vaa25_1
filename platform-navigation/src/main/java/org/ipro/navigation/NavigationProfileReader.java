package org.ipro.navigation;

import java.util.Optional;

/** Current-user-only read model. Only absence of an assignment means developer defaults. */
public interface NavigationProfileReader {
    Optional<NavigationProfile> currentProfile();

    /** Uses the catalog already loaded for this menu snapshot. */
    Optional<NavigationProfile> currentProfile(NavigationCatalog catalog);
}
