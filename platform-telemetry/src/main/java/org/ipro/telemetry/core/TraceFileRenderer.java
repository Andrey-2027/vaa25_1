package org.ipro.telemetry.core;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Человекочитаемый trace-файл (L2): шапка операции + дерево фреймов
 * с текстами и временем SQL-стейтментов.
 */
public final class TraceFileRenderer {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    private TraceFileRenderer() {
    }

    public static String render(Operation operation) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("TRACE traceId=").append(operation.getTraceId())
                .append(" user=").append(operation.getUser())
                .append("\n  started: ").append(TIME.format(operation.getStartedAt()))
                .append("\n  operation: ").append(operation.getName())
                .append("  durationMs=").append(ms(operation.getDurationNanos()))
                .append("  sqlCount=").append(operation.getSqlCount());
        String entity = operation.getContextValue(MdcKeys.ENTITY);
        String entityId = operation.getContextValue(MdcKeys.ENTITY_ID);
        if (entity != null) {
            sb.append("  entity=").append(entity);
        }
        if (entityId != null) {
            sb.append("  entityId=").append(entityId);
        }
        sb.append('\n');
        if (operation.getErrorMessage() != null) {
            sb.append("  ERROR: ").append(operation.getErrorMessage()).append('\n');
        }
        appendFrame(sb, operation, 1);
        String entityData = operation.getContextValue(MdcKeys.ENTITY_DATA);
        if (entityData != null) {
            sb.append("\n  entityData: ").append(entityData).append('\n');
        }
        return sb.toString();
    }

    private static void appendFrame(StringBuilder sb, Frame frame, int depth) {
        String indent = "  ".repeat(depth);
        sb.append(indent).append(frame.getName())
                .append("  durationMs=").append(ms(frame.getDurationNanos()))
                .append("  sqlCount=").append(frame.getSqlCount())
                .append(frame.isFailed() ? "  FAILED" : "")
                .append('\n');
        for (SqlRecord record : frame.getTraceSqls()) {
            sb.append(indent).append("  SQL (")
                    .append(ms(record.executionNanos()))
                    .append(" ms): ").append(record.sql()).append('\n');
        }
        for (Frame child : frame.getChildren()) {
            appendFrame(sb, child, depth + 1);
        }
    }

    private static double ms(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }
}