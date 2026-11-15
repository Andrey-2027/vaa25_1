package org.ipro.query;

import java.util.Objects;

/**
 * Чтение проекции отклонено платформой по понятной причине. Экран различает причины по
 * {@link #reason()}, а не по тексту сообщения.
 */
public final class QueryReadRefusedException extends IllegalStateException {
    /** Причина отказа. */
    public enum Reason {
        /** Результат больше предела режима IN_MEMORY. */
        ROW_LIMIT,
        /** Квота одновременных выполнений занята. */
        QUOTA_BUSY,
        /** Права пользователя изменились во время чтения. */
        ACCESS_CHANGED,
        /** Запрос или его результат недоступны пользователю (guard, C5, RLS). */
        ACCESS_DENIED
    }

    private final Reason reason;

    public QueryReadRefusedException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
