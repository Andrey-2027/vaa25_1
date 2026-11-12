package org.ip.views.admin;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** ADMIN rule at entry, activation and every draft command. */
@Component
public final class EntityDraftAccess {
    private final boolean enabled;
    private final EntityExplorerAccess access;
    public EntityDraftAccess(boolean enabled, EntityExplorerAccess access) {
        this.enabled = enabled; this.access = access;
    }
    @org.springframework.beans.factory.annotation.Autowired
    public EntityDraftAccess(EntityExplorerAccess access) {
        this(true, access);
    }
    public boolean allows() { return enabled && access.allows(); }
    String principal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }
}
