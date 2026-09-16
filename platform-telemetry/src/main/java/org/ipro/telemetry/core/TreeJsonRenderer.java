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
 * При {@code includeSql=true} (L2-трасса) в каждый фрейм добавляется
 * список выполненных SQL: {"sql":[{"text":"select ..","ms":..},...]}.
 */
public final class TreeJsonRenderer {

    private static final ObjectMapper mapper = new ObjectMapper();

    private TreeJsonRenderer() {
    }

    public static String render(Operation operation) {
        return render(operation, false);
    }

    public static String render(Operation operation, boolean includeSql) {
        try {
            return mapper.writeValueAsString(toMap(operation, includeSql));
        } catch (Exception e) {
            return "{\"error\":\"tree json failed\"}";
        }
    }

    private static Map<String, Object> toMap(Frame frame, boolean includeSql) {
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
            String entityData = operation.getContextValue(MdcKeys.ENTITY_DATA);
            if (entityData != null) {
                try {
                    map.put("entityData", mapper.readTree(entityData));
                } catch (Exception e) {
                    map.put("entityData", entityData);
                }
            }
        }
        if (includeSql && !frame.getTraceSqls().isEmpty()) {
            List<Map<String, Object>> sqls = new ArrayList<>();
            for (SqlRecord record : frame.getTraceSqls()) {
                Map<String, Object> sqlMap = new LinkedHashMap<>();
                sqlMap.put("text", record.sql());
                sqlMap.put("ms", round(record.executionNanos() / 1_000_000.0));
                sqls.add(sqlMap);
            }
            map.put("sql", sqls);
        }
        List<Map<String, Object>> children = new ArrayList<>();
        for (Frame child : frame.getChildren()) {
            children.add(toMap(child, includeSql));
        }
        map.put("children", children);
        return map;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
