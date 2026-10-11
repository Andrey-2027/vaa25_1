package org.ipro.navigation.store;

import org.ipro.navigation.NavigationCatalog;
import org.ipro.navigation.NavigationProfile;
import org.ipro.navigation.NavigationVisibility;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Owner-internal conversion; never return a managed entity beyond a transaction. */
public final class NavigationSnapshots {
    private NavigationSnapshots() {}
    public static NavigationProfile snapshot(NavigationProfileEntity profile,
            NavigationOverrideRepository overrides, NavigationCatalog catalog) {
        Map<String, NavigationVisibility> values = new TreeMap<>();
        overrides.findByProfile_Id(profile.getId()).forEach(row -> values.put(row.getTargetKey(), row.getVisibility()));
        var stale = new TreeSet<>(values.keySet());
        stale.removeAll(catalog.keys());
        return new NavigationProfile(profile.getId(), profile.getVersion(), profile.getName(), values, stale);
    }
}
