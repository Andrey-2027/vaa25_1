package org.ipro.settings.fixture;

import org.ipro.metadata.annotation.FieldType;
import org.ipro.settings.setting.Setting;
import org.ipro.settings.setting.SettingsGroup;

import org.ip.subsystem.Subsystems;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Тестовая группа настроек: по полю на каждый {@link FieldType} из {@code @Setting}.
 * Значения в коде — ДЕФОЛТЫ, которые {@link org.ipro.settings.SettingsService} отдаёт при
 * отсутствии admin-перекрытия в {@code setting_value}.
 */
@SettingsGroup(subsystem = Subsystems.Directories.class)
public class FixtureTypedSettings {

    public enum TestMode { A, B, C }

    @Setting(type = FieldType.TEXT)
    public String text = "по умолчанию";

    @Setting(type = FieldType.INTEGER)
    public Integer integer = 5;

    @Setting(type = FieldType.DECIMAL)
    public BigDecimal decimal = new BigDecimal("1.50");

    @Setting(type = FieldType.BOOLEAN)
    public Boolean bool = true;

    @Setting(type = FieldType.DATE)
    public LocalDate date = LocalDate.of(2020, 1, 1);

    @Setting(type = FieldType.DATETIME)
    public LocalDateTime dateTime = LocalDateTime.of(2020, 1, 1, 10, 30);

    @Setting(type = FieldType.ENUM)
    public TestMode mode = TestMode.B;

    @Setting(type = FieldType.ENTITY_REFERENCE)
    public Long headId = 7L;

    @Setting(secret = true)
    public String smtpPassword = "секрет-из-кода";
}