package org.ipro.unidr.run;

import org.ipro.rls.RlsContext;
import org.ipro.rls.RlsCurrentUser;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Pilot switch and subject admission at every public service entry. No bypass for UI reports. */
public final class UniDrAccess {
    private final BooleanSupplier enabled;
    private final RlsCurrentUser currentUser;
    public UniDrAccess(BooleanSupplier enabled, RlsCurrentUser currentUser) {
        this.enabled = Objects.requireNonNull(enabled); this.currentUser = Objects.requireNonNull(currentUser);
    }
    public String requireUser() {
        if (!enabled.getAsBoolean()) throw new UniDrRunException(UniDrRunException.Code.DISABLED, "Пилот UniDR выключен");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken
            || RlsContext.isBypassed()) throw denied();
        String user;
        try { user = currentUser.requireAuthenticatedUsername(); }
        catch (org.springframework.security.access.AccessDeniedException denied) { throw denied(); }
        if (!user.equals(authentication.getName()) || "system".equals(user)) throw denied();
        return user;
    }
    public void requireOwner(String owner) { if (!requireUser().equals(owner)) throw denied(); }
    private UniDrRunException denied() { return new UniDrRunException(UniDrRunException.Code.ACCESS_DENIED, "Нет доступа к запуску UniDR"); }
}
