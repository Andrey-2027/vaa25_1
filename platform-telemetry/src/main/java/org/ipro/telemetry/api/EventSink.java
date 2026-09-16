package org.ipro.telemetry.api;

/**
 * Единственная точка записи событий телеметрии.
 * <p>
 * {@link #accept} — асинхронный путь (fire-and-forget) для перф-событий:
 * потеря при переполнении очереди допустима и фиксируется счётчиком.
 * <p>
 * {@link #acceptDurable} — надёжный путь для SECURITY-событий:
 * потери недопустимы, запись подтверждается до возврата.
 */
public interface EventSink {

    void accept(TelemetryEvent event);

    void acceptDurable(TelemetryEvent event);

    /** Персист L0-агрегата окна в perf_stats (некритичный путь, fire-and-forget). */
    void acceptStats(AggregateStats stats);

    /**
     * Запись field-level аудита (entity_change_log). По умолчанию — no-op:
     * реализует AsyncEventSink. Асинхронный путь (fire-and-forget).
     */
    default void acceptFieldChange(FieldChangeRecord change) {
    }

    /**
     * Durable-запись field-level аудита: синхронная запись с подтверждением,
     * при активной транзакции — присоединяется к ней (REQUIRED), т.е. коммитится
     * атомарно с бизнес-изменением.
     */
    default void acceptFieldChangeDurable(FieldChangeRecord change) {
    }

    void flush();

    /** true — sink-заглушка (БД-журнал выключен); события не пишутся никуда. */
    default boolean isNoop() {
        return false;
    }
}
