package org.ip.navigation;

import org.ip.repository.UserRepository;
import org.ipro.data.EntityDataAccess;
import org.ipro.navigation.NavigationAdministrationAccess;
import org.ipro.navigation.NavigationUserDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Application owns authentication, ROLE_ADMIN and the mapping of logins to immutable User.id. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.navigation.enabled", havingValue = "true")
public class ApplicationNavigationSecurityConfiguration {
    @Bean
    NavigationAdministrationAccess navigationAdministrationAccess() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return authenticated(authentication) && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        };
    }

    @Bean
    NavigationUserDirectory navigationUserDirectory(EntityDataAccess data, UserRepository users) {
        return new ApplicationNavigationUserDirectory(data, users);
    }

    static Authentication authentication() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!authenticated(authentication)) throw new AccessDeniedException("Authentication required for navigation");
        return authentication;
    }

    private static boolean authenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
