package org.ipro.telemetry.core;

/**
 * Статический выключатель перехвата и guard от рекурсии.
 * <p>
 * {@link #isInsideLogging()} поднимается на время записи собственных
 * событий телеметрии — чтобы журнал не логировал сам себя.
 * {@link #isEnabled()} отражает общий включатель подсистемы.
 */
public final class TelemetryGuard {

    private static volatile boolean enabled = true;
    private static final ThreadLocal<Boolean> INSIDE_LOGGING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private TelemetryGuard() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isInsideLogging() {
        return INSIDE_LOGGING.get();
    }

    public static void insideLogging(Runnable action) {
        INSIDE_LOGGING.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            INSIDE_LOGGING.remove();
        }
    }
}