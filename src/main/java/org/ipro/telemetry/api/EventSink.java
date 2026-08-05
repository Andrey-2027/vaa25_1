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

    void flush();
}
