package com.example.generated.harness;

import java.util.List;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsRoleResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The only identity seam of the harness: it reads the test-authenticated subject and declares no
 * roles. Grants are created by the test fixture, never by the generated entity.
 */
@Configuration(proxyBeanMethods = false)
class HarnessIdentityConfiguration {

    @Bean
    RlsCurrentUser rlsCurrentUser() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || authentication instanceof AnonymousAuthenticationToken) {
                return null;
            }
            return authentication.getName();
        };
    }

    @Bean
    RlsRoleResolver rlsRoleResolver() {
        return username -> List.of();
    }
}
