package org.ip.navigation;

import org.ip.model.User;
import org.ip.repository.UserRepository;
import org.ipro.data.EntityDataAccess;
import org.ipro.navigation.NavigationUserDirectory;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;

/** Bounded canonical lookup; raw repository is used only for authentication identity translation. */
final class ApplicationNavigationUserDirectory implements NavigationUserDirectory {
    private final EntityDataAccess data;
    private final UserRepository users;

    ApplicationNavigationUserDirectory(EntityDataAccess data, UserRepository users) {
        this.data = data;
        this.users = users;
    }

    @Override
    public List<NavigationUserDirectory.UserOption> lookup(String term, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("User lookup limit must be 1..50");
        return data.lookup(User.class, term, limit).stream()
            .map(user -> new NavigationUserDirectory.UserOption(user.getId().toString(), user.getUsername())).toList();
    }

    @Override
    public boolean exists(String key) {
        if (key == null || !key.matches("[1-9][0-9]*")) return false;
        try {
            return data.detail(User.class, Long.parseLong(key)).isPresent();
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    @Override
    public String currentUserKey() {
        String username = ApplicationNavigationSecurityConfiguration.authentication().getName();
        return users.findByUsername(username).map(user -> user.getId().toString())
            .orElseThrow(() -> new AccessDeniedException("Authenticated navigation user does not exist"));
    }
}
