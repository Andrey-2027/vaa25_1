package org.ipro.telemetry.model;

import java.time.Instant;

import org.ipro.telemetry.api.EventType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Журнал L1: аномалии (медленные операции с деревом фреймов в payload,
 * ошибки, N+1, security-события). Пишется асинхронным writer'ом EventSink
 * батчами; чтение — UI журнала (этап 9).
 */
@Entity
@Table(name = "operation_log",
        indexes = {
                @Index(name = "idx_operation_log_started_at", columnList = "started_at"),
                @Index(name = "idx_operation_log_trace_id", columnList = "trace_id"),
                @Index(name = "idx_operation_log_user_id", columnList = "user_id"),
                @Index(name = "idx_operation_log_operation", columnList = "operation")
        })
public class OperationLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private EventType eventType;

    @Column(nullable = false, length = 16)
    private String level;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "duration_ms")
    private Double durationMs;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(length = 255)
    private String operation;

    @Column(length = 255)
    private String entity;

    @Column(name = "entity_id", length = 64)
    private String entityId;

    @Column(name = "sql_count")
    private Integer sqlCount;

    @Column(name = "sql_total_ms")
    private Double sqlTotalMs;

    @Column
    private boolean n1;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(columnDefinition = "text")
    private String payload;

    public Long getId() {
        return id;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Double getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Double durationMs) {
        this.durationMs = durationMs;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getEntity() {
        return entity;
    }

    public void setEntity(String entity) {
        this.entity = entity;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public Integer getSqlCount() {
        return sqlCount;
    }

    public void setSqlCount(Integer sqlCount) {
        this.sqlCount = sqlCount;
    }

    public Double getSqlTotalMs() {
        return sqlTotalMs;
    }

    public void setSqlTotalMs(Double sqlTotalMs) {
        this.sqlTotalMs = sqlTotalMs;
    }

    public boolean isN1() {
        return n1;
    }

    public void setN1(boolean n1) {
        this.n1 = n1;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
