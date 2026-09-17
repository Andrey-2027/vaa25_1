package org.ipro.metadata;

/**
 * Стабильные коды диагностик метаданных (C4.2).
 *
 * <p>Код, а не текст сообщения: на него опираются и разрешённые исключения, и тесты.
 * Текст можно улучшать, не ломая политику.</p>
 */
public final class MetadataDiagnosticCodes {

    private MetadataDiagnosticCodes() {
    }

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
