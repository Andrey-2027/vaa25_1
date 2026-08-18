package org.ip.settings;

import org.ipro.metadata.annotation.FieldType;
import org.ip.subsystem.Subsystems;
import org.ipro.settings.setting.Setting;
import org.ipro.settings.setting.SettingsGroup;

/**
 * Пилотный раздел констант (механизм см. org.ipro.settings). Значения полей в коде — дефолты
 * разработчика; перекрытия администратора хранятся в setting_value и доступны через
 * SettingsService.get/set. Реальные настройки приложения добавляются так же: класс в
 * org.ip.settings + @SettingsGroup(subsystem = маркер из Subsystems) + поля @Setting.
 */
@SettingsGroup(subsystem = Subsystems.Directories.class, title = "Параметры системы")
public class SystemSettings {

    @Setting(type = FieldType.INTEGER, label = "Глубина кода номенклатуры")
    private int codeDepth = 6;

    @Setting(type = FieldType.BOOLEAN, label = "Контролировать уникальность артикула")
    private boolean articleUnique = true;

    @Setting(type = FieldType.TEXT, label = "Шаблон номера накладной")
    private String deliveryNumberPattern = "{prefix}{yyyy}-{seq:000000}";

    public int getCodeDepth() {
        return codeDepth;
    }

    public void setCodeDepth(int codeDepth) {
        this.codeDepth = codeDepth;
    }

    public boolean isArticleUnique() {
        return articleUnique;
    }

    public void setArticleUnique(boolean articleUnique) {
        this.articleUnique = articleUnique;
    }

    public String getDeliveryNumberPattern() {
        return deliveryNumberPattern;
    }

    public void setDeliveryNumberPattern(String deliveryNumberPattern) {
        this.deliveryNumberPattern = deliveryNumberPattern;
    }
}