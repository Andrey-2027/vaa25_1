package org.ipro.telemetry.api;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Прикладной пользователь текущего потока. Дефолтная реализация читает
 * SecurityContextHolder (платформенная зависимость); при необходимости
 * переопределяется приложением.
 */
public interface UserContext {

    String currentUsername();

    static UserContext defaultInstance() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || "anonymousUser".equals(authentication.getPrincipal())) {
                return "system";
            }
            return authentication.getName();
        };
    }
}
