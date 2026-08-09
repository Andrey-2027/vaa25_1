package org.ipro.telemetry.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Журнал изменений полей сущностей (field-level audit, этап 10):
 * одна строка на факт изменения записи (INSERT/UPDATE/DELETE), список
 * изменённых полей — JSON-массив в payload. Пишется AsyncEventSink
 * (durable-путь, присоединение к бизнес-транзакции); чтение — UI через
 * FieldAuditQueryService.
 */
@Entity
@Table(name = "entity_change_log",
        indexes = {
                @Index(name = "ix_ecl_entity", columnList = "entity, entity_id, changed_at"),
                @Index(name = "ix_ecl_user", columnList = "user_id, changed_at")
        })
public class EntityChangeLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "change_type", nullable = false, length = 10)
    private String changeType;

    @Column(nullable = false, length = 200)
    private String entity;

    @Column(name = "entity_id", nullable = false, length = 100)
    private String entityId;

    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(name = "trace_id", length = 40)
    private String traceId;

    @Column(name = "field_count")
    private Integer fieldCount;

    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    public Long getId() {
        return id;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(Instant changedAt) {
        this.changedAt = changedAt;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Integer getFieldCount() {
        return fieldCount;
    }

    public void setFieldCount(Integer fieldCount) {
        this.fieldCount = fieldCount;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
