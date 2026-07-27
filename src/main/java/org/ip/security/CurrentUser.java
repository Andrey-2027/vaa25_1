package org.ip.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Имя текущего аутентифицированного пользователя. Та же логика, что уже была отдельно
 * в FormSettingsService.currentUsername() и AuditConfig.AuditorAwareImpl — вынесена сюда,
 * т.к. понадобилась в третьем месте (GridFormViewService). FormSettingsService/AuditConfig
 * не трогал (работают и так) — рефакторинг их на этот класс можно сделать отдельно,
 * не в рамках этой задачи.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || "anonymousUser".equals(authentication.getPrincipal())) {
            return "system";
        }
        return authentication.getName();
    }
}
