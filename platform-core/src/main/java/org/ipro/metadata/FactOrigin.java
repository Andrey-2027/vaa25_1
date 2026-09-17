package org.ipro.metadata;

/**
 * Источник эффективного факта метаданных (C4.2, ADR-0007 §6).
 *
 * <p>Каждый effective-факт ({@code required}, {@code type}, {@code reference}) хранит, откуда
 * он получен. Без этого вывод из Bean Validation/JPA невозможно отличить от явного
 * объявления: одно и то же значение может быть следствием контракта записи или
 * осознанного UI-переопределения, и диагностика должна показывать именно источник.</p>
 */
public enum FactOrigin {

    /** Явно объявлено в {@code @FieldMetadata}/{@code @Lookup}. */
    EXPLICIT,

    /** Выведено из Bean Validation ({@code @NotNull}, {@code @NotBlank}, {@code @NotEmpty}). */
    BEAN_VALIDATION,

    /** Выведено из JPA-маппинга ({@code nullable = false}, тип ассоциации). */
    JPA_MAPPING,

    /** Выведено из Java-типа поля. */
    JAVA_TYPE,

    /** Платформенный fallback: ни объявления, ни контракта записи. */
    PLATFORM_DEFAULT
}
