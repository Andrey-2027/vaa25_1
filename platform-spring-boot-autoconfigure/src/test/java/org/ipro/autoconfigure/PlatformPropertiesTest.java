package org.ipro.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт {@link PlatformProperties}: свойство сканирования типизировано, задётся
 * приложением и обязательно — отсутствие значения останавливает старт, как прежний
 * {@code @Value} без default. Default-значения у свойства нет и появиться не должно:
 * платформа не угадывает имя прикладного пакета.
 */
class PlatformPropertiesTest {

    @Test
    void requiredValueRejectsUnsetProperty() {
        PlatformProperties properties = new PlatformProperties();
        assertThatThrownBy(properties::requiredSubsystemScanPackage)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("platform.subsystem-scan-package");
    }

    @Test
    void requiredValueRejectsBlankProperty() {
        PlatformProperties properties = new PlatformProperties();
        properties.setSubsystemScanPackage("   ");
        assertThatThrownBy(properties::requiredSubsystemScanPackage)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("platform.subsystem-scan-package");
    }

    @Test
    void requiredValueReturnsBoundValue() {
        PlatformProperties properties = new PlatformProperties();
        properties.setSubsystemScanPackage("org.example.app");
        assertThat(properties.requiredSubsystemScanPackage()).isEqualTo("org.example.app");
    }

    /** Свойство реально привязывается префиксом {@code platform.*} через релаксированный binding. */
    @Test
    void propertyBindsFromApplicationContext() {
        new ApplicationContextRunner()
            .withUserConfiguration(EnableProperties.class)
            .withPropertyValues("platform.subsystem-scan-package=org.example.app")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(PlatformProperties.class).getSubsystemScanPackage())
                    .isEqualTo("org.example.app");
            });
    }

    @Configuration
    @EnableConfigurationProperties(PlatformProperties.class)
    static class EnableProperties {
    }
}
