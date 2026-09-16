package org.ipro.telemetry.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.ipro.telemetry.api.FieldChangeRecord;
import org.ipro.telemetry.api.UserContext;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Накопитель изменений одной записи в рамках операции: одна строка
 * {@code entity_change_log} на факт изменения записи (INSERT/UPDATE/DELETE).
 * <p>
 * Скалярные поля копятся списком {@code FieldChange}; изменения строк
 * табличных частей (row-level Pre*Event) агрегируются в сводки по имени
 * класса строки: {@code {"field":"ReceivingDocumentItem","added":2,"removed":1,"changed":0}}.
 * <p>
 * При merge-событиях за операцию (например, delete(): сначала удаление строк
 * replaceAll, потом шапки) записи схлопываются по ключу (entity, entityId);
 * тип DELETE имеет приоритет над INSERT/UPDATE.
 */
final class FieldAuditAccumulator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String entity;
    private final String entityId;
    private final Instant changedAt;
    private final String userId;
    private final String traceId;
    private final List<FieldChange> scalars = new ArrayList<>();
    /** field -> [added, removed, changed]. */
    private final Map<String, int[]> sections = new LinkedHashMap<>();
    private String changeType;

    FieldAuditAccumulator(String entity, String entityId) {
        this.entity = entity;
        this.entityId = entityId;
        this.changedAt = Instant.now();
        this.userId = resolveUser();
        this.traceId = resolveTraceId();
    }

    /**
     * user_id строки аудита. Обычно — MDC USER, который операция положила при
     * beginFrame. НО: flush сущности в UI-потоке происходит при КОММИТЕ
     * транзакции — уже после завершения операции и очистки MDC (clearMdc),
     * поэтому здесь фолбэк на security-контекст текущего потока
     * (SecurityContextHolder на потоке коммита ещё жив; без него — "system").
     */
    private static String resolveUser() {
        String user = MDC.get(MdcKeys.USER);
        if (user != null && !user.isBlank()) {
            return user;
        }
        try {
            return UserContext.defaultInstance().currentUsername();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** trace_id: MDC (активная операция) или traceId операции, только что завершившейся на этом потоке. */
    private static String resolveTraceId() {
        String traceId = MDC.get(MdcKeys.TRACE_ID);
        return traceId != null ? traceId : OperationContext.lastCompletedTraceId();
    }

    void setType(String type) {
        if (changeType == null || "DELETE".equals(type)) {
            changeType = type;
        }
    }

    void addScalar(FieldChange change) {
        scalars.add(change);
    }

    void addSection(String field, String op) {
        int[] counts = sections.computeIfAbsent(field, k -> new int[3]);
        switch (op) {
            case "INSERT" -> counts[0]++;
            case "DELETE" -> counts[1]++;
            case "UPDATE" -> counts[2]++;
            default -> {
            }
        }
    }

    boolean isEmpty() {
        return scalars.isEmpty() && sections.isEmpty();
    }

    /** Строка для персиста: payload — JSON-массив, fieldCount — число элементов. */
    FieldChangeRecord toRecord() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (FieldChange change : scalars) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", change.field());
            item.put("old", change.oldValue());
            item.put("new", change.newValue());
            items.add(item);
        }
        for (Map.Entry<String, int[]> entry : sections.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", entry.getKey());
            item.put("added", entry.getValue()[0]);
            item.put("removed", entry.getValue()[1]);
            item.put("changed", entry.getValue()[2]);
            items.add(item);
        }
        return new FieldChangeRecord(
                changedAt,
                changeType,
                entity,
                entityId,
                userId,
                traceId,
                items.size(),
                toJson(items));
    }

    private static String toJson(List<Map<String, Object>> items) {
        try {
            return MAPPER.writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }
}
