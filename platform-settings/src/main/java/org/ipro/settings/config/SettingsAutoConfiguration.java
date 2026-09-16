package org.ipro.settings.config;

import org.ipro.settings.SettingsRegistry;
import org.ipro.settings.SettingsReverseReferenceSource;
import org.ipro.settings.SettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Auto-Configuration подсистемы констант. Пакет {@code org.ipro.settings} не попадает в
 * component-scan приложения (базовый пакет {@code org.ip}), поэтому бины и каталог объявляются
 * здесь.
 *
 * <p>D2 → D3 (модуль констант): подсистема выехала в собственный артефакт, поэтому здесь же
 * объявляются её {@code @EntityScan} и {@code @EnableJpaRepositories}. Раньше и сущности, и
 * репозитории перечисляли приложение и платформенный хаб {@code RlsAutoConfiguration};
 * повторная декларация аннотации считалась опасной — persistence-срез D2 проверил, что это
 * не так: две независимые декларации не перекрываются, и именно на этом свойстве держится
 * вся схема саморегистрации модулей.</p>
 */
@AutoConfiguration
@EntityScan("org.ipro.settings")
@EnableJpaRepositories("org.ipro.settings")
public class SettingsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SettingsRegistry settingsRegistry(
            @Value("${settings.scan-package}") String basePackage) {
        return new SettingsRegistry(basePackage);
    }

    @Bean
    @ConditionalOnMissingBean
    public SettingsService settingsService(SettingsRegistry settingsRegistry,
                                           org.ipro.settings.SettingValueRepository repository) {
        return new SettingsService(settingsRegistry, repository);
    }

    /** Обратные ссылки "настройка → сущность" (ENTITY_REFERENCE) для ReferenceIndex. */
    @Bean
    @ConditionalOnMissingBean
    public SettingsReverseReferenceSource settingsReverseReferenceSource(
            @Value("${settings.scan-package}") String basePackage) {
        return new SettingsReverseReferenceSource(basePackage);
    }
}