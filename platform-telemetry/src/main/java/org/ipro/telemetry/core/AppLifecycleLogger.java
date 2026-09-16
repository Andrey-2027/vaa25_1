package org.ipro.telemetry.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.EventType;
import org.ipro.telemetry.api.TelemetryEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.SpringVersion;

/**
 * Старт/стоп приложения (EventType.APP): версия Java, Spring, Vaadin.
 * В отличие от SECURITY пишется через обычный accept — при штатном
 * завершении ContextClosedEvent приходит до close() sink, а close()
 * сбрасывает очередь.
 */
public final class AppLifecycleLogger {

    private final EventSink sink;
    private final String appName;

    public AppLifecycleLogger(EventSink sink, String appName) {
        this.sink = sink;
        this.appName = appName;
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        emit("INFO", "app:started");
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        emit("INFO", "app:stopped");
    }

    private void emit(String level, String operation) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("app", appName);
        values.put("java", System.getProperty("java.version", "?"));
        values.put("spring", SpringVersion.getVersion());
        values.put("vaadin", vaadinVersion());
        String payload = PayloadJson.json(values);
        sink.accept(new TelemetryEvent(
                EventType.APP,
                level,
                Instant.now(),
                null,
                null,
                "system",
                null,
                operation,
                null,
                null,
                null,
                null,
                false,
                null,
                payload));
    }

    /**
     * Версия Vaadin — рефлексией по имени, без зависимости на UI: модуля platform-telemetry
     * Vaadin не видит даже на компиляции. Без Vaadin в classpath — {@code "?"}.
     */
    private static String vaadinVersion() {
        try {
            Class<?> version = Class.forName("com.vaadin.flow.server.Version");
            return String.valueOf(version.getMethod("getFullVersion").invoke(null));
        } catch (ReflectiveOperationException | NoClassDefFoundError | RuntimeException e) {
            return "?";
        }
    }
}