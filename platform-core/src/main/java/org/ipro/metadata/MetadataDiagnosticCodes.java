package org.ipro.metadata;

/**
 * Стабильные коды диагностик метаданных (C4.2).
 *
 * <p>Код, а не текст сообщения: на него опираются и разрешённые исключения, и тесты.
 * Текст можно улучшать, не ломая политику.</p>
 */
public final class MetadataDiagnosticCodes {
    public static final String LEGACY_FORM_COLUMNS = "LEGACY_FORM_COLUMNS";
    public static final String DUPLICATE_FORM_COLUMN_SOURCE = "DUPLICATE_FORM_COLUMN_SOURCE";

    private MetadataDiagnosticCodes() {
    }

    /** Final backend classification sources: errors cannot be silenced by legacy allowances. */
    public static final String CLASSIFICATION_SOURCE_FAILED = "CLASSIFICATION_SOURCE_FAILED";
    public static final String CLASSIFICATION_SOURCE_MISSING = "CLASSIFICATION_SOURCE_MISSING";
    public static final String CLASSIFICATION_SOURCE_DUPLICATE = "CLASSIFICATION_SOURCE_DUPLICATE";
    public static final String ENTITY_CLASSIFICATION_CONFLICT = "ENTITY_CLASSIFICATION_CONFLICT";
    public static final String INTERNED_GENERIC_WRITES = "INTERNED_GENERIC_WRITES";
    public static final String NUMBERING_ROLE_KIND_CONFLICT = "NUMBERING_ROLE_KIND_CONFLICT";
    public static final String LOOKUP_TARGET_NOT_ACCESSIBLE = "LOOKUP_TARGET_NOT_ACCESSIBLE";

    public static final String INVALID_ENTITY_METADATA = "INVALID_ENTITY_METADATA";
    public static final String INVALID_FIELD_METADATA = "INVALID_FIELD_METADATA";

    /** Owner-capability: указанное свойство отсутствует в типе. */
    public static final String CATALOG_OWNER_PROPERTY_MISSING = "CATALOG_OWNER_PROPERTY_MISSING";

    /** Owner-capability: свойство не является поддержанной одиночной ManyToOne-связью. */
    public static final String CATALOG_OWNER_ASSOCIATION_INVALID = "CATALOG_OWNER_ASSOCIATION_INVALID";

    /** Owner-capability: цель не является допустимым managed CATALOG root с Long-ID. */
    public static final String CATALOG_OWNER_TARGET_INVALID = "CATALOG_OWNER_TARGET_INVALID";

    /** Owner-capability: декларации обязательности противоречат owner-контракту. */
    public static final String CATALOG_OWNER_REQUIRED_MISMATCH = "CATALOG_OWNER_REQUIRED_MISMATCH";

    /** Owner-capability: несколько конфликтующих объявлений в иерархии. */
    public static final String CATALOG_OWNER_DUPLICATE = "CATALOG_OWNER_DUPLICATE";

    /** Owner-capability объявлена у документа, строки секции или другого недопустимого child. */
    public static final String CATALOG_OWNER_SUBJECT_INVALID = "CATALOG_OWNER_SUBJECT_INVALID";

    /** UI отказывается от обязательности, хотя контракт записи её требует. */
    public static final String UI_OPTIONAL_SERVER_REQUIRED = "UI_OPTIONAL_SERVER_REQUIRED";

    /** UI требует заполнения, хотя сервер допускает пустое значение. */
    public static final String UI_REQUIRED_SERVER_OPTIONAL = "UI_REQUIRED_SERVER_OPTIONAL";

    /** Явный {@code required} совпадает с выводом из контракта записи — объявление избыточно. */
    public static final String REDUNDANT_REQUIRED = "REDUNDANT_REQUIRED";

    /** Явный {@code type} совпадает с выводом из Java-типа/JPA — объявление избыточно. */
    public static final String REDUNDANT_TYPE = "REDUNDANT_TYPE";

    /** Явная цель {@code @Lookup.entity} совпадает с типом ссылки — объявление избыточно. */
    public static final String REDUNDANT_LOOKUP_TARGET = "REDUNDANT_LOOKUP_TARGET";

    /** Объявленный тип поля противоречит Java-типу/JPA-ассоциации. */
    public static final String TYPE_CONFLICT = "TYPE_CONFLICT";

    /** Явная цель выбора противоречит типу ссылки. */
    public static final String REFERENCE_CONFLICT = "REFERENCE_CONFLICT";

    /** Ссылка ведёт на тип без метаданных — форма выбора для неё не строится. */
    public static final String REFERENCE_TARGET_NOT_METADATA = "REFERENCE_TARGET_NOT_METADATA";

    /** Java-тип поля не распознан и заменён платформенным fallback'ом. */
    public static final String FALLBACK_TYPE = "FALLBACK_TYPE";

    /** Разрешённое исключение объявлено, но соответствующего условия больше нет. */
    public static final String STALE_ALLOWANCE = "STALE_ALLOWANCE";

    /**
     * Исключение пытается погасить код вне согласованного перечня
     * ({@link MetadataAllowance#ALLOWABLE_CODES}). Конфликты контракта поля остаются
     * ошибками старта.
     */
    public static final String DISALLOWED_ALLOWANCE = "DISALLOWED_ALLOWANCE";
}
