package org.ipro.settings;

import org.ipro.settings.fixture.FixtureTypedSettings;
import org.ipro.settings.fixture.FixtureTypedSettings.TestMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Срез: настоящий {@code setting_value} (H2) + {@code SettingsRegistry} над тестовым
 * каталогом (basePackage {@code org.ipro.settings.fixture}), {@code SettingsService} создаётся
 * вручную. Проверяется семантика констант: дефолт из кода, admin-перекрытие, тип-колонка по
 * FieldType, секреты, fail-fast по каталогу.
 */
@DataJpaTest
@ContextConfiguration(classes = org.ip.Application.class)
@EnableJpaRepositories(basePackages = {"org.ip", "org.ipro.settings"})
class SettingsServiceSliceIT {

    @Autowired
    private SettingValueRepository repository;

    private SettingsService service;

    @BeforeEach
    void buildService() {
        SettingsRegistry registry = new SettingsRegistry("org.ipro.settings.fixture");
        registry.afterPropertiesSet();
        service = new SettingsService(registry, repository);
    }

    @Test
    void defaultsComeFromCodeWhenNoOverride() {
        assertThat(service.getString(FixtureTypedSettings.class, "text")).isEqualTo("по умолчанию");
        assertThat(service.getBoolean(FixtureTypedSettings.class, "bool")).isTrue();
        assertThat(service.getLong(FixtureTypedSettings.class, "integer")).isEqualTo(5);
        assertThat(service.getBigDecimal(FixtureTypedSettings.class, "decimal"))
            .isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(service.getLocalDate(FixtureTypedSettings.class, "date"))
            .isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(service.getLocalDateTime(FixtureTypedSettings.class, "dateTime"))
            .isEqualTo(LocalDateTime.of(2020, 1, 1, 10, 30));
        assertThat(service.getEnum(FixtureTypedSettings.class, "mode", TestMode.class))
            .isEqualTo(TestMode.B);
        assertThat(service.getEntityRefId(FixtureTypedSettings.class, "headId")).isEqualTo(7L);
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void overridePersistsTypedColumnAndOverwrites() {
        service.set(FixtureTypedSettings.class, "integer", 42);
        assertThat(service.getLong(FixtureTypedSettings.class, "integer")).isEqualTo(42);

        service.set(FixtureTypedSettings.class, "integer", 99);
        assertThat(service.getLong(FixtureTypedSettings.class, "integer")).isEqualTo(99);
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void globalScopeMeansSingleRowForKey() {
        service.set(FixtureTypedSettings.class, "text", "из-админки");
        service.set(FixtureTypedSettings.class, "bool", false);

        assertThat(repository.findAll()).hasSize(2);
        assertThat(service.getString(FixtureTypedSettings.class, "text")).isEqualTo("из-админки");
        assertThat(service.getBoolean(FixtureTypedSettings.class, "bool")).isFalse();
    }

    @Test
    void decimalAndEnumRoundTrips() {
        service.set(FixtureTypedSettings.class, "decimal", new BigDecimal("2.25"));
        assertThat(service.getBigDecimal(FixtureTypedSettings.class, "decimal"))
            .isEqualByComparingTo(new BigDecimal("2.25"));

        service.set(FixtureTypedSettings.class, "mode", TestMode.A);
        assertThat(service.getEnum(FixtureTypedSettings.class, "mode", TestMode.class))
            .isEqualTo(TestMode.A);

        // в типизированной колонке лежит имя — не инстанс
        SettingValue row = repository.findAll().stream()
            .filter(r -> "FixtureTypedSettings.mode".equals(r.getSettingKey()))
            .findFirst().orElseThrow();
        assertThat(row.getEnumValue()).isEqualTo("A");
    }

    @Test
    void secretIsReadableButNeverStored() {
        assertThat(service.getString(FixtureTypedSettings.class, "smtpPassword"))
            .isEqualTo("секрет-из-кода");

        assertThatThrownBy(() -> service.set(FixtureTypedSettings.class, "smtpPassword", "хак"))
            .isInstanceOf(IllegalStateException.class);

        assertThat(repository.findAll()).isEmpty();
        assertThat(service.getString(FixtureTypedSettings.class, "smtpPassword"))
            .isEqualTo("секрет-из-кода");
    }

    @Test
    void resetToDefaultClearsOverride() {
        service.set(FixtureTypedSettings.class, "integer", 42);
        assertThat(service.getLong(FixtureTypedSettings.class, "integer")).isEqualTo(42);

        service.resetToDefault(FixtureTypedSettings.class, "integer");

        assertThat(service.getLong(FixtureTypedSettings.class, "integer")).isEqualTo(5);
        assertThat(repository.findAll()).isEmpty();

        // повторный сброс без перекрытия — no-op
        service.resetToDefault(FixtureTypedSettings.class, "integer");
        assertThat(service.getLong(FixtureTypedSettings.class, "integer")).isEqualTo(5);
    }

    @Test
    void unknownSettingIsRejected() {
        assertThatThrownBy(() -> service.getString(FixtureTypedSettings.class, "noSuchField"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rlsDimensionDerivedFromSubsystem() {
        assertThat(service.rlsDimensionOf(FixtureTypedSettings.class))
            .isEqualTo("SETTINGS:Directories");
    }

    @Test
    void badSubsystemMarkerFailsAtRebuild() {
        SettingsRegistry bad = new SettingsRegistry("org.ipro.settings.fixturebad");
        assertThatThrownBy(bad::afterPropertiesSet)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("@Subsystem");
    }
}