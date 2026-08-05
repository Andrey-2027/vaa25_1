package org.ipro.telemetry.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Корневой фрейм операции: хранит traceId, пользователя, контекстные
 * ключи MDC, добавленные за время операции (для корректной очистки),
 * сообщение об ошибке при неудачном завершении и счётчики
 * нормализованных SQL для N+1-детекции.
 */
public final class Operation extends Frame {

    private final String traceId;
    private final String user;
    private final String sessionId;
    private final Instant startedAt;
    private final List<String> mdcKeysAdded = new ArrayList<>();
    private final Map<String, Integer> sqlCounts = new HashMap<>();
    private String errorMessage;
    private int droppedFrames;
    private int nodeCount = 1;

    public Operation(String name, String traceId, String user, String sessionId) {
        super(name);
        this.traceId = traceId;
        this.user = user;
        this.sessionId = sessionId;
        this.startedAt = Instant.now();
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public void incrementNodeCount() {
        nodeCount++;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getUser() {
        return user;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void addMdcKey(String key) {
        mdcKeysAdded.add(key);
    }

    public List<String> getMdcKeysAdded() {
        return mdcKeysAdded;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getDroppedFrames() {
        return droppedFrames;
    }

    public void incrementDroppedFrames() {
        droppedFrames++;
    }

    public void countSql(String normalizedSql) {
        sqlCounts.merge(normalizedSql, 1, Integer::sum);
    }

    /** N+1-эвристика: один и тот же нормализованный SQL >= threshold раз за операцию. */
    public boolean isN1(int threshold) {
        for (int count : sqlCounts.values()) {
            if (count >= threshold) {
                return true;
            }
        }
        return false;
    }
}