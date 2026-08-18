package org.ipro.settings.config;

import org.ipro.settings.SettingsRegistry;
import org.ipro.settings.SettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-Configuration подсистемы констант. Пакет {@code org.ipro.settings} не попадает в
 * component-scan приложения (базовый пакет {@code org.ip}), поэтому бины и каталог объявляются
 * здесь. Репозитории подсистемы добавлены в явный {@code @EnableJpaRepositories} класса
 * {@code RlsAutoConfiguration} (повторная декларация во второй авто-конфигурации перекрывала
 * бы список базовых пакетов — см. javadoc NumberingAutoConfiguration).
 */
@AutoConfiguration
public class SettingsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SettingsRegistry settingsRegistry(
            @Value("${settings.scan-package:org.ip.settings}") String basePackage) {
        return new SettingsRegistry(basePackage);
    }

    @Bean
    @ConditionalOnMissingBean
    public SettingsService settingsService(SettingsRegistry settingsRegistry,
                                           org.ipro.settings.SettingValueRepository repository) {
        return new SettingsService(settingsRegistry, repository);
    }
}