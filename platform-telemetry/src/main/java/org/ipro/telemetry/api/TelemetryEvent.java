package org.ipro.telemetry.api;

import java.time.Instant;

/**
 * Единое событие телеметрии. Переносится в EventSink и записывается
 * в файл и/или БД-журнал.
 */
public record TelemetryEvent(
        EventType type,
        String level,
        Instant startedAt,
        Long durationMs,
        String traceId,
        String userId,
        String sessionId,
        String operation,
        String entity,
        String entityId,
        Integer sqlCount,
        Long sqlTotalMs,
        boolean n1,
        String errorMessage,
        String payload) {
}
