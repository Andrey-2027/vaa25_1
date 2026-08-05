package org.ipro.telemetry.core;

import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.EventType;
import org.ipro.telemetry.api.TelemetryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Обработчик завершения операций (L1): при аномалии (медленно/ошибка/
 * dropped-фреймы/N+1) формирует событие с деревом фреймов в payload_json
 * и отправляет в {@link EventSink} (БД-журнал). Синхронная запись в файл —
 * только если БД-журнал выключен (noop-sink).
 */
public final class SlowOperationHandler implements OperationCompletionHandler {

    private static final Logger log = LoggerFactory.getLogger("ipro.telemetry.operation");

    private final long methodThresholdMs;
    private final int n1Threshold;
    private final EventSink sink;

    public SlowOperationHandler(long methodThresholdMs, int n1Threshold, EventSink sink) {
        this.methodThresholdMs = methodThresholdMs;
        this.n1Threshold = n1Threshold;
        this.sink = sink;
    }

    @Override
    public void onOperationComplete(Operation operation) {
        double durationMs = operation.getDurationNanos() / 1_000_000.0;
        boolean n1 = operation.isN1(n1Threshold);
        String error = operation.getErrorMessage();

        boolean anomaly = error != null
                || operation.getDroppedFrames() > 0
                || durationMs >= methodThresholdMs
                || n1;
        if (!anomaly) {
            return;
        }

        String level = error != null ? "ERROR" : "WARN";
        String treeJson = TreeJsonRenderer.render(operation);
        sink.accept(new TelemetryEvent(
                EventType.PERF_METHOD,
                level,
                operation.getStartedAt(),
                Math.round(durationMs),
                operation.getTraceId(),
                operation.getUser(),
                operation.getSessionId(),
                operation.getName(),
                null,
                null,
                operation.getSqlCount(),
                operation.getSqlTotalNanos() == 0 ? null : operation.getSqlTotalNanos() / 1_000_000L,
                n1,
                error,
                treeJson));

        if (sink.isNoop()) {
            logLine(operation, durationMs, n1, error, "\n" + TreeRenderer.render(operation));
        } else {
            logLine(operation, durationMs, n1, error, "");
        }
    }

    private void logLine(Operation operation, double durationMs, boolean n1,
                         String error, String tree) {
        String suffix = String.format("op=%s user=%s traceId=%s duration=%.2f ms sql=%d",
                operation.getName(), operation.getUser(), operation.getTraceId(),
                durationMs, operation.getSqlCount());
        if (n1) {
            suffix += " N+1";
        }
        if (error != null) {
            log.error("{} error={}{}", suffix, error, tree);
        } else {
            log.warn("{} droppedFrames={}{}", suffix, operation.getDroppedFrames(), tree);
        }
    }
}
