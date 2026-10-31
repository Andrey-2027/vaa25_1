package com.example.entitytypes.config;

import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsRoleResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class IdentityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService consumerUsers(PasswordEncoder encoder,
            @Value("${consumer.demo-password}") String password) {
        if (password.isBlank()) {
            throw new IllegalArgumentException("CONSUMER_DEMO_PASSWORD must not be blank");
        }
        String encoded = encoder.encode(password);
        return new InMemoryUserDetailsManager(
                User.withUsername("writer").password(encoded).roles("USER").build(),
                User.withUsername("reader").password(encoded).roles("USER").build(), User.withUsername("denied").password(encoded).roles("USER").build());
    }

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
    RlsRoleResolver rlsRoleResolver(UserDetailsService users) {
        return username -> {
            if (username == null || username.isBlank()) {
                return java.util.List.of();
            }
            try {
                return users.loadUserByUsername(username).getAuthorities().stream()
                        .map(authority -> authority.getAuthority())
                        .filter(authority -> authority.startsWith("ROLE_"))
                        .map(authority -> authority.substring("ROLE_".length()))
                        .toList();
            } catch (UsernameNotFoundException unknownUser) {
                return java.util.List.of();
            }
        };
    }
}
