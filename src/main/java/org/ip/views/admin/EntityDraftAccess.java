package org.ip.views.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Same pilot flag and ADMIN rule at entry, activation and every draft command. */
@Component
public final class EntityDraftAccess {
    private final boolean enabled;
    private final EntityExplorerAccess access;
    public EntityDraftAccess(@Value("${app.entity-explorer.ux-pilot.enabled:false}") boolean enabled, EntityExplorerAccess access) {
        this.enabled = enabled; this.access = access;
    }
    public boolean allows() { return enabled && access.allows(); }
    String principal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }
}
