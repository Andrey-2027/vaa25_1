package org.ipro.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.ipro.crud.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Типизированное хранилище значений настроек. Строка адресуется тройкой
 * (setting_key, scope_type, scope_id) — в v1 исключительно сфера GLOBAL с sentinel
 * {@code scopeId = 0} (никогда не NULL: NULL в unique-колонке Postgres допускает дубли).
 * Будущий scoped-доступ (BRANCH-настройки "своё для филиала") — это новые строки той же
 * таблицы с (scope_type, scope_id) другой комбинации, без смены схемы.
 *
 * <p>Значение лежит ровно в ОДНОЙ типизированной колонке — какой именно определяет
 * {@code FieldType} из {@code @Setting} (см. SettingsService), в БД тип не дублируется.</p>
 *
 * <p>Секретные настройки (@Setting(secret=true)) здесь НЕ хранятся вообще — см. SettingsService.</p>
 */
@Entity
@Table(name = "setting_value",
    uniqueConstraints = @UniqueConstraint(columnNames = {"setting_key", "scope_type", "scope_id"}))
public class SettingValue extends BaseEntity {

    /** "{GroupSimpleName}.{fieldName}" — см. SettingsRegistry. */
    @Column(name = "setting_key", nullable = false, length = 255)
    private String settingKey;

    /** Название сферы (в v1 — "GLOBAL"): будущее "BRANCH" и т.п. */
    @Column(name = "scope_type", nullable = false, length = 50)
    private String scopeType = "GLOBAL";

    /** Идентификатор в сфере; sentinel 0 = сфера целиком (для GLOBAL всегда 0). */
    @Column(name = "scope_id", nullable = false)
    private long scopeId;

    @Column(name = "string_value")
    private String stringValue;

    @Column(name = "integer_value")
    private Long integerValue;

    @Column(name = "decimal_value")
    private BigDecimal decimalValue;

    @Column(name = "bool_value")
    private Boolean boolValue;

    @Column(name = "date_value")
    private LocalDate dateValue;

    @Column(name = "date_time_value")
    private LocalDateTime dateTimeValue;

    @Column(name = "enum_value")
    private String enumValue;

    @Column(name = "entity_ref_id")
    private Long entityRefId;

    protected SettingValue() {
    }

    public SettingValue(String settingKey, String scopeType, long scopeId) {
        this.settingKey = settingKey;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }

    public String getSettingKey() {
        return settingKey;
    }

    public void setSettingKey(String settingKey) {
        this.settingKey = settingKey;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public long getScopeId() {
        return scopeId;
    }

    public void setScopeId(long scopeId) {
        this.scopeId = scopeId;
    }

    public String getStringValue() {
        return stringValue;
    }

    public void setStringValue(String stringValue) {
        this.stringValue = stringValue;
    }

    public Long getIntegerValue() {
        return integerValue;
    }

    public void setIntegerValue(Long integerValue) {
        this.integerValue = integerValue;
    }

    public BigDecimal getDecimalValue() {
        return decimalValue;
    }

    public void setDecimalValue(BigDecimal decimalValue) {
        this.decimalValue = decimalValue;
    }

    public Boolean getBoolValue() {
        return boolValue;
    }

    public void setBoolValue(Boolean boolValue) {
        this.boolValue = boolValue;
    }

    public LocalDate getDateValue() {
        return dateValue;
    }

    public void setDateValue(LocalDate dateValue) {
        this.dateValue = dateValue;
    }

    public LocalDateTime getDateTimeValue() {
        return dateTimeValue;
    }

    public void setDateTimeValue(LocalDateTime dateTimeValue) {
        this.dateTimeValue = dateTimeValue;
    }

    public String getEnumValue() {
        return enumValue;
    }

    public void setEnumValue(String enumValue) {
        this.enumValue = enumValue;
    }

    public Long getEntityRefId() {
        return entityRefId;
    }

    public void setEntityRefId(Long entityRefId) {
        this.entityRefId = entityRefId;
    }
}