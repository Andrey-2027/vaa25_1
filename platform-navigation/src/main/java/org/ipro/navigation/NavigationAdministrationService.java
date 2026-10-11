package org.ipro.navigation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Protected named operations. Every method requires the application administration policy. */
public interface NavigationAdministrationService {
    List<NavigationProfile> listProfiles();
    NavigationProfile profile(long id);
    NavigationProfile createProfile(String name);
    NavigationProfile renameProfile(long id, long expectedVersion, String name);
    /** Replace known keys; preserve stale stored keys. DEFAULT is never persisted. */
    NavigationProfile saveProfile(long id, long expectedVersion, Map<String, NavigationVisibility> values);
    NavigationProfile resetProfile(long id, long expectedVersion);
    List<NavigationUserDirectory.UserOption> lookupUsers(String term, int limit);
    void assignUser(String userKey, long profileId);
    void unassignUser(String userKey);
    Optional<Long> assignmentForUser(String userKey);
}
