package org.ipro.telemetry.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON-представление дерева фреймов — payload_json записи operation_log
 * (читается UI журнала, этап 9). Формат:
 * <pre>
 * {"name":"op","durationMs":..,"sqlCount":..,"sqlTotalMs":..,"sqlMaxMs":..,
 *  "failed":false,"droppedFrames":0,"children":[{"name":"svc.m()",...}]}
 * </pre>
 */
public final class TreeJsonRenderer {

    private static final ObjectMapper mapper = new ObjectMapper();

    private TreeJsonRenderer() {
    }

    public static String render(Operation operation) {
        try {
            return mapper.writeValueAsString(toMap(operation));
        } catch (Exception e) {
            return "{\"error\":\"tree json failed\"}";
        }
    }

    private static Map<String, Object> toMap(Frame frame) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", frame.getName());
        map.put("durationMs", round(frame.getDurationNanos() / 1_000_000.0));
        map.put("sqlCount", frame.getSqlCount());
        map.put("sqlTotalMs", round(frame.getSqlTotalNanos() / 1_000_000.0));
        map.put("sqlMaxMs", round(frame.getSqlMaxNanos() / 1_000_000.0));
        map.put("failed", frame.isFailed());
        if (frame instanceof Operation operation) {
            map.put("user", operation.getUser());
            map.put("traceId", operation.getTraceId());
            map.put("droppedFrames", operation.getDroppedFrames());
        }
        List<Map<String, Object>> children = new ArrayList<>();
        for (Frame child : frame.getChildren()) {
            children.add(toMap(child));
        }
        map.put("children", children);
        return map;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
