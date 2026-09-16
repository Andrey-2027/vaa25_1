package org.ipro.telemetry.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.EventType;
import org.ipro.telemetry.api.TelemetryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Обработчик завершения операций в окне L2-трассировки: пишет событие
 * EventType.TRACE (payload — полное дерево фреймов с SQL-текстами) в
 * EventSink и человекочитаемый trace-файл {@code trace_<traceId>.txt}
 * в trace-dir. Для нетрассируемых операций — no-op.
 */
public final class TraceDumpHandler implements OperationCompletionHandler {

    private static final Logger log = LoggerFactory.getLogger("ipro.telemetry.trace");

    private final EventSink sink;
    private final Path traceDir;
    private final int n1Threshold;

    public TraceDumpHandler(EventSink sink, String traceDir, int n1Threshold) {
        this.sink = sink;
        this.traceDir = Paths.get(traceDir);
        this.n1Threshold = n1Threshold;
    }

    @Override
    public void onOperationComplete(Operation operation) {
        if (!operation.isTraceActive()) {
            return;
        }
        try {
            dump(operation);
        } catch (RuntimeException e) {
            log.warn("trace dump failed for {}: {}", operation.getName(), e.toString());
        }
    }

    private void dump(Operation operation) {
        double durationMs = operation.getDurationNanos() / 1_000_000.0;
        String json = TreeJsonRenderer.render(operation, true);
        sink.accept(new TelemetryEvent(
                EventType.TRACE,
                "INFO",
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
                operation.isN1(n1Threshold),
                null,
                json));

        try {
            Files.createDirectories(traceDir);
            Path file = traceDir.resolve("trace_" + operation.getTraceId() + ".txt");
            Files.writeString(file, TraceFileRenderer.render(operation));
            log.info("trace written: {} (op={} user={} duration={} ms sql={})",
                    file.getFileName(), operation.getName(), operation.getUser(),
                    Math.round(durationMs), operation.getSqlCount());
        } catch (IOException e) {
            log.warn("trace file write failed: {}", e.toString());
        }
    }
}