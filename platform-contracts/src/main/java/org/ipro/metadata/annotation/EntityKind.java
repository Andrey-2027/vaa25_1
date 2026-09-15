package org.ipro.metadata.annotation;

/**
 * Семантический archetype сущности платформы.
 *
 * <p>{@link #AUTO} является только значением декларации: {@code MetadataResolver}
 * выводит kind из стандартного базового класса, а для обычного наследника
 * {@code BaseEntity} возвращает {@link #PLAIN}. В resolved metadata значение AUTO
 * не допускается.</p>
 */
public enum EntityKind {

    /** Вывести kind из стандартного базового класса, иначе использовать PLAIN. */
    AUTO,

    /** Обычная сущность без стандартной семантики справочника/документа/регистра. */
    PLAIN,

    /** Справочник: стандартный golden path code + name. */
    CATALOG,

    /** Документ: стандартный golden path number + date. */
    DOCUMENT,

    /** Будущий информационный регистр; полная семантика реализуется отдельно. */
    INFORMATION_REGISTER
}
