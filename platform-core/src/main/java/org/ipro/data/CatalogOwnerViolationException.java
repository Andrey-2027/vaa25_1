package org.ipro.data;

import java.util.Objects;

/**
 * Нарушение owner-контракта справочника при записи.
 *
 * <p>Это ошибка контракта данных, а не отказ в доступе: nullable-владелец или перенос
 * сохранённой записи к другому владельцу запрещены самим объявлением capability. Поэтому
 * ни право на именованную операцию, ни lifecycle hook, ни подмена managed-экземпляра не
 * превращают нарушение в разрешённую запись.</p>
 *
 * <p>Исключение возникает до нумерации, валидации, хуков и SQL-мутации (preflight) либо
 * перед самой записью (финальная проверка после callbacks).</p>
 */
public final class CatalogOwnerViolationException extends IllegalStateException {

    /** Причина нарушения; код стабилен и используется тестами. */
    public enum Reason {
        /** Обязательный владелец не задан. */
        OWNER_REQUIRED,
        /** Владелец сохранённой записи отличается от записываемого. */
        OWNER_IMMUTABLE,
        /** Явный owner-контекст команды не совпадает с association. */
        CONTEXT_MISMATCH,
        /** Доверенное сохранённое состояние владельца недоступно. */
        STATE_UNREADABLE
    }

    private final Reason reason;

    public CatalogOwnerViolationException(Reason reason, String message) {
        super(reason.name() + ": " + Objects.requireNonNull(message, "message must not be null"));
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public CatalogOwnerViolationException(Reason reason, String message, Throwable cause) {
        super(reason.name() + ": " + Objects.requireNonNull(message, "message must not be null"), cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason reason() {
        return reason;
    }
}
