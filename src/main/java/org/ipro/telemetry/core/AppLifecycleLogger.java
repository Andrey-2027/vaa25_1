package org.ipro.telemetry.core;

import java.time.Instant;

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
        String payload = "{\"app\":\"" + appName
                + "\",\"java\":\"" + System.getProperty("java.version", "?")
                + "\",\"spring\":\"" + SpringVersion.getVersion()
                + "\",\"vaadin\":\"" + vaadinVersion() + "\"}";
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

    private static String vaadinVersion() {
        try {
            return com.vaadin.flow.server.Version.getFullVersion();
        } catch (NoClassDefFoundError | RuntimeException e) {
            return "?";
        }
    }
}