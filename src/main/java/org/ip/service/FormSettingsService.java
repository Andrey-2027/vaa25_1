package org.ip.service;

import jakarta.transaction.Transactional;
import org.ip.model.UserFormSettings;
import org.ip.repository.UserFormSettingsRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Пользовательские настройки форм (ключ-значение за текущим пользователем) — хранилище
 * для 1С-стиля "настройки формы запоминаются за пользователем".
 *
 * Текущий пользователь определяется так же, как в аудите (AuditConfig.AuditorAwareImpl):
 * из SecurityContext, с fallback "system" для неаутентифицированного контекста.
 */
@Service
@Transactional
public class FormSettingsService {

    private final UserFormSettingsRepository repository;

    public FormSettingsService(UserFormSettingsRepository repository) {
        this.repository = repository;
    }

    /** Значение настройки текущего пользователя, empty — если не сохранена. */
    public Optional<String> get(String key) {
        return repository.findByUsernameAndSettingKey(currentUsername(), key)
            .map(UserFormSettings::getSettingValue);
    }

    /** Сохранить (или перезаписать) значение настройки текущего пользователя. */
    public void put(String key, String value) {
        String username = currentUsername();
        UserFormSettings settings = repository.findByUsernameAndSettingKey(username, key)
            .orElseGet(() -> {
                UserFormSettings fresh = new UserFormSettings();
                fresh.setUsername(username);
                fresh.setSettingKey(key);
                return fresh;
            });
        settings.setSettingValue(value);
        repository.save(settings);
    }

    /** Удалить настройку текущего пользователя (возврат к поведению по умолчанию). */
    public void remove(String key) {
        repository.deleteByUsernameAndSettingKey(currentUsername(), key);
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || "anonymousUser".equals(authentication.getPrincipal())) {
            return "system";
        }
        return authentication.getName();
    }
}
