package org.ip.security;

import org.ipro.rls.RlsCurrentUser;
import org.springframework.stereotype.Component;

/**
 * Реализация {@link RlsCurrentUser} для этого приложения: делегирует в
 * {@link CurrentUser#username()} (Spring Security context).
 */
@Component
public class SecurityRlsUser implements RlsCurrentUser {

    @Override
    public String username() {
        return CurrentUser.username();
    }
}