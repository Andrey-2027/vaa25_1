package org.ip.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Пользовательская настройка формы (1С-стиль "настройки форм хранятся за пользователем").
 * Одна строка = одно значение настройки для пары (пользователь, ключ).
 *
 * Сейчас единственный потребитель — состав колонок Формы Списка
 * (ключ "listform.columns.&lt;Entity&gt;", значение — пути колонок через ";"), но структура
 * ключ-значение сознательно универсальная: сюда же лягут ширины колонок, свёрнутость
 * панелей и т.п.
 *
 * Служебная таблица: без @EntityMetadata — в реестрах приложения не показывается.
 */
@Entity
@Table(name = "user_form_settings",
       uniqueConstraints = @UniqueConstraint(columnNames = {"username", "setting_key"}))
public class UserFormSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(name = "setting_key", nullable = false)
    private String settingKey;

    @Column(name = "setting_value", columnDefinition = "text")
    private String settingValue;

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getSettingKey() {
        return settingKey;
    }

    public void setSettingKey(String settingKey) {
        this.settingKey = settingKey;
    }

    public String getSettingValue() {
        return settingValue;
    }

    public void setSettingValue(String settingValue) {
        this.settingValue = settingValue;
    }
}
