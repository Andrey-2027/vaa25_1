package org.ipro.navigation;

import java.util.Map;
import java.util.Set;

/** Detached read model; version covers the name and all visibility overrides. */
public record NavigationProfile(long id, long version, String name,
                                Map<String, NavigationVisibility> overrides, Set<String> staleKeys) {
    public NavigationProfile {
        overrides = Map.copyOf(overrides);
        staleKeys = Set.copyOf(staleKeys);
    }
}
