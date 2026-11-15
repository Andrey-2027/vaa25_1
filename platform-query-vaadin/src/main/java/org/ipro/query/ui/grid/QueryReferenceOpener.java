package org.ipro.query.ui.grid;

/** Открытие формы по ссылке из результата запроса. Права перепроверяет приложение. */
@FunctionalInterface
public interface QueryReferenceOpener {
    /** Возвращает причину отказа или {@code null}, если форма открыта. */
    String open(Class<?> entityType, long id);

    default String typeName(Class<?> type) {
        return "запись";
    }
}
