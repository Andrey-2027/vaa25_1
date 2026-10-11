package org.ipro.navigation;

import java.util.List;
import java.util.Objects;

/** Application adapter. Keys are immutable user IDs, never login names or roles. */
public interface NavigationUserDirectory {
    List<UserOption> lookup(String term, int limit);
    boolean exists(String userKey);
    /** Must reject unauthenticated/unknown users, rather than return a fallback identity. */
    String currentUserKey();

    record UserOption(String key, String title) {
        public UserOption {
            Objects.requireNonNull(key, "user key");
            Objects.requireNonNull(title, "user title");
        }
    }
}
