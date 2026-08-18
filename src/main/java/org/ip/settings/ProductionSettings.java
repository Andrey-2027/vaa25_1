package org.ip.settings;

import org.ip.subsystem.Subsystems;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.settings.setting.Setting;
import org.ipro.settings.setting.SettingsGroup;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Операционные константы производственного контура. Привязаны к маркеру {@code Subsystems.Production}
 * — сфера значений и право доступа: раздел редактируется по CHECK_ONLY-измерению
 * {@code "SETTINGS:Production"} (см. AccessGrantAdminService/Админ-экран настроек).
 *
 * Значения-дефолты живут здесь (в коде), перекрытия администратора — в {@code setting_value}.
 */
@SettingsGroup(subsystem = Subsystems.Production.class)
public class ProductionSettings {

    public enum Shift { DAY, NIGHT, ALL }

    /** Плановая цель выпуска на день, шт. */
    @Setting(type = FieldType.INTEGER, label = "План выпуска на день")
    public int dailyProductionTarget = 100;

    /** Автоподтверждение операций технологического маршрута. */
    @Setting(type = FieldType.BOOLEAN, label = "Автоподтверждение операций")
    public boolean autoConfirmOperations = false;

    /** Допустимое отклонение фактического выпуска от плана, %. */
    @Setting(type = FieldType.DECIMAL, label = "Допуск отклонения, %")
    public BigDecimal tolerancePercent = new BigDecimal("1.00");

    /** Начало производственного года (для отчётности и серий). */
    @Setting(type = FieldType.DATE, label = "Начало производственного года")
    public LocalDate productionYearStart = LocalDate.of(2026, 1, 1);

    /** Рабочая смена по умолчанию. */
    @Setting(type = FieldType.ENUM, label = "Смена по умолчанию")
    public Shift shift = Shift.DAY;

    /** Внешний API-ключ интеграции (в БД НЕ хранится — только код). */
    @Setting(secret = true, label = "API-ключ интеграции")
    public String integrationApiKey = "prod-integration-key";
}
