package org.ipro.navigation;

import java.util.Optional;

/** Current-user-only read model. Only absence of an assignment means developer defaults. */
public interface NavigationProfileReader {
    Optional<NavigationProfile> currentProfile();
}
