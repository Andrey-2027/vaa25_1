package org.ip.metadata.annotation;

/**
 * Тип поля формы. Определяет, какой Vaadin-компонент создаст FieldFactory.
 * Если в @FieldMetadata указан type = AUTO, тип определится по Java-типу поля.
 */
public enum FieldType {
    /** Определить автоматически по Java-типу поля */
    AUTO,
    /** String → TextField */
    TEXT,
    /** String (длинный) → TextArea */
    TEXT_AREA,
    /** Integer/Long → IntegerField */
    INTEGER,
    /** BigDecimal/Double → BigDecimalField */
    DECIMAL,
    /** Boolean → Checkbox */
    BOOLEAN,
    /** LocalDate → DatePicker */
    DATE,
    /** LocalDateTime → DateTimePicker */
    DATETIME,
    /** Enum → ComboBox<Enum> */
    ENUM,
    /** @ManyToOne → EntityField (1С-стиль "Поле ввода") */
    ENTITY_REFERENCE,
    /** String с валидацией email → EmailField */
    EMAIL,
    /** String скрытое → PasswordField */
    PASSWORD
}
