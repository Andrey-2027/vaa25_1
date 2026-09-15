package org.ipro.data;

/**
 * Класс экспозиции persistence type в canonical data path (C4, см. ADR-0007 §2).
 *
 * <p>Один type имеет ровно одну экспозицию. Membership в {@code ManagedEntityCatalog}
 * сам по себе не предоставляет публичный data handle: его выдаёт descriptor, а не факт
 * присутствия типа в JPA metamodel.</p>
 */
public enum EntityExposure {

    /** Самостоятельный metadata-driven root с canonical data handle. */
    STANDARD_ROOT,

    /** Строка объявленной owned-секции: доступна только через aggregate boundary владельца. */
    OWNED_ROW,

    /** Platform/report/settings/telemetry storage без публичного entity facade. */
    INTERNAL_STORE,

    /**
     * Тип вне persistence unit / вне классифицированного каталога: canonical data handle
     * не выдаётся. Отдельная экспозиция, а не «permissive root»: неизвестный тип — это
     * отказ до RLS и SQL, а не скрытое разрешение (ADR-0007 §2, fail-closed).
     */
    UNCLASSIFIED
}
