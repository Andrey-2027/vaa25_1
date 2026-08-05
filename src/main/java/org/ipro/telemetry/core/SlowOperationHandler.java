package org.ipro.telemetry.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Обработчик завершения операций (L1-минимум на Вехе 1): пишет WARN в лог
 * для медленных операций (> порога) и ERROR — при ошибке, с деревом фреймов.
 * Веха 2 заменит файловую запись на EventSink/БД-журнал.
 */
public final class SlowOperationHandler implements OperationCompletionHandler {

    private static final Logger log = LoggerFactory.getLogger("ipro.telemetry.operation");

    private final long methodThresholdMs;

    public SlowOperationHandler(long methodThresholdMs) {
        this.methodThresholdMs = methodThresholdMs;
    }

    @Override
    public void onOperationComplete(Operation operation) {
        double durationMs = operation.getDurationNanos() / 1_000_000.0;
        String tree = TreeRenderer.render(operation);

        if (operation.getErrorMessage() != null) {
            log.error("op={} user={} traceId={} duration={} ms droppedFrames={}\n{}",
                    operation.getName(), operation.getUser(), operation.getTraceId(),
                    String.format("%.2f", durationMs), operation.getDroppedFrames(), tree);
            return;
        }
        if (operation.getDroppedFrames() > 0) {
            log.warn("op={} user={} traceId={} duration={} ms droppedFrames={}\n{}",
                    operation.getName(), operation.getUser(), operation.getTraceId(),
                    String.format("%.2f", durationMs), operation.getDroppedFrames(), tree);
            return;
        }
        if (durationMs >= methodThresholdMs) {
            log.warn("op={} user={} traceId={} duration={} ms (threshold {} ms)\n{}",
                    operation.getName(), operation.getUser(), operation.getTraceId(),
                    String.format("%.2f", durationMs), methodThresholdMs, tree);
        }
    }
}