package org.ip.service;

import org.ipro.form.spi.FormSettingsStore;
import org.ipro.telemetry.api.Measured;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Адаптер пользовательских настроек форм к платформенному {@link FormSettingsStore}:
 * делегирование 1:1.
 */
@Measured
@Component
public class FormSettingsStoreAdapter implements FormSettingsStore {

    private final FormSettingsService service;

    public FormSettingsStoreAdapter(FormSettingsService service) {
        this.service = service;
    }

    @Override
    public Optional<String> get(String key) {
        return service.get(key);
    }

    @Override
    public void put(String key, String value) {
        service.put(key, value);
    }

    @Override
    public void remove(String key) {
        service.remove(key);
    }
}
