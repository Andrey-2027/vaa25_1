package org.ip.model;

/**
 * Тип значения атрибута (единый механизм для «Атрибутов номенклатуры» и «Наборов характеристик» КСУ).
 *
 * <p>Все четыре типа хранят значения едиными строками {@link AttributeValue}:
 * <ul>
 *   <li>{@code STRING} — «Строка»: свободный ввод, дедуп по верхнему регистру;</li>
 *   <li>{@code NUMBER} — «Число»: свободный ввод с нормализацией в каноническую форму;</li>
 *   <li>{@code REF} — «Ссылка»: ссылка на строку целевого справочника
 *       ({@code AttributeType.targetDictionary} + {@code AttributeValue.refId});</li>
 *   <li>{@code ENUM} — «Внутренний справочник»: значения живут в {@code AttributeValue}
 *       с {@code attrType} = этот тип (тип и есть свой словарь).</li>
 * </ul>
 */
public enum AttributeValueType {

    STRING("Строка"),
    NUMBER("Число"),
    REF("Ссылка"),
    ENUM("Внутренний справочник");

    private final String label;

    AttributeValueType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}