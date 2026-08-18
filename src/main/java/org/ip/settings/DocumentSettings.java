package org.ip.settings;

import org.ip.subsystem.Subsystems;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.settings.setting.Setting;
import org.ipro.settings.setting.SettingsGroup;

import java.time.LocalDateTime;

/**
 * Константы документного контура (приёмно-сдаточные накладные). Сфера и право доступа —
 * маркер {@code Subsystems.ProductionDocuments} / измерение {@code "SETTINGS:ProductionDocuments"}.
 */
@SettingsGroup(subsystem = Subsystems.ProductionDocuments.class)
public class DocumentSettings {

    public enum DocPriority { LOW, NORMAL, HIGH }

    /** За сколько дней до срока напоминать о не принятых накладных. */
    @Setting(type = FieldType.INTEGER, label = "Напоминание, дней")
    public int receivingDocReminderDays = 3;

    /** Блокировать выдачу номеров с пропусками в серии. */
    @Setting(type = FieldType.BOOLEAN, label = "Блокировать пропуски номеров")
    public boolean blockNumberingGaps = false;

    /** Комментарий приёмки по умолчанию. */
    @Setting(type = FieldType.TEXT, label = "Комментарий приёмки по умолчанию")
    public String defaultAcceptComment = "";

    /** Приоритет накладной по умолчанию. */
    @Setting(type = FieldType.ENUM, label = "Приоритет по умолчанию")
    public DocPriority defaultPriority = DocPriority.NORMAL;

    /** Кто подписывает накладные по умолчанию (id пользователя — на будущее подписывание). */
    @Setting(type = FieldType.ENTITY_REFERENCE, label = "Подписант по умолчанию (id)")
    public Long defaultSignerId = 1L;

    /** Следующая плановая очистка архива документов. */
    @Setting(type = FieldType.DATETIME, label = "Плановая очистка архива")
    public LocalDateTime archiveCleanupAt = LocalDateTime.of(2026, 1, 1, 3, 0);
}
