package org.ipro.telemetry.core;

import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.EventType;
import org.ipro.telemetry.api.TelemetryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Обработчик завершения операций (L1).
 * <ul>
 *   <li>ACTION-операции (явные: формы, security не тут) пишутся в журнал
 *       ВСЕГДА — это журнал действий пользователя (1С-стиль); уровень
 *       INFO (успех) / WARN (аномалия) / ERROR (ошибка).</li>
 *   <li>PERF_METHOD-операции (AOP-перехват) — только при аномалии
 *       (медленно/ошибка/dropped-фреймы/N+1), уровень WARN/ERROR.</li>
 * </ul>
 * В payload_json — дерево фреймов; entity/entityId берутся из контекста
 * операции.
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
        boolean isAction = operation.getEventType() == EventType.ACTION;

        boolean anomaly = error != null
                || operation.getDroppedFrames() > 0
                || durationMs >= methodThresholdMs
                || n1;
        if (!isAction && !anomaly) {
            return;
        }

        String level = error != null ? "ERROR" : (anomaly ? "WARN" : "INFO");
        String treeJson = TreeJsonRenderer.render(operation);
        TelemetryEvent event = new TelemetryEvent(
                operation.getEventType(),
                level,
                operation.getStartedAt(),
                Math.round(durationMs),
                operation.getTraceId(),
                operation.getUser(),
                operation.getSessionId(),
                operation.getName(),
                operation.getContextValue(MdcKeys.ENTITY),
                operation.getContextValue(MdcKeys.ENTITY_ID),
                operation.getSqlCount(),
                operation.getSqlTotalNanos() == 0 ? null : operation.getSqlTotalNanos() / 1_000_000L,
                n1,
                error,
                treeJson);
        if ("ERROR".equals(level)) {
            sink.acceptDurable(event);
        } else {
            sink.accept(event);
        }

        if (sink.isNoop() || anomaly) {
            logLine(operation, durationMs, n1, error,
                    sink.isNoop() ? "\n" + TreeRenderer.render(operation) : "");
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
        } else if (operation.getEventType() == EventType.ACTION && durationMs < methodThresholdMs
                && operation.getDroppedFrames() == 0 && !n1) {
            log.info("{}{}", suffix, tree);
        } else {
            log.warn("{} droppedFrames={}{}", suffix, operation.getDroppedFrames(), tree);
        }
    }
}