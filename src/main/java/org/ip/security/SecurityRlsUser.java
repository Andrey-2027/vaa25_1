package org.ip.security;

import org.ipro.rls.RlsCurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Реализация {@link RlsCurrentUser} для этого приложения: читает Spring Security context
 * напрямую — та же логика, что раньше жила в platform-классе {@code org.ipro.security.CurrentUser}.
 *
 * <p>До D3.4 anonymous/отсутствие аутентификации даёт «system», как и раньше: spring-security
 * {@code AnonymousAuthenticationToken} отфильтрован по principal.</p>
 */
@Component
public class SecurityRlsUser implements RlsCurrentUser {

    @Override
    public String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || "anonymousUser".equals(authentication.getPrincipal())) {
            return "system";
        }
        return authentication.getName();
    }
}
