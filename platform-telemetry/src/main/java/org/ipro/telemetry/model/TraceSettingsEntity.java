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
 * Окно L2-трассировки «включено для пользователя до момента времени».
 * Заполняется из UI (этап 7): пока trace_until > now — для операций
 * пользователя строится полное дерево фреймов с параметрами SQL.
 */
@Entity
@Table(name = "trace_settings",
        indexes = @Index(name = "idx_trace_settings_user_id", columnList = "user_id"))
public class TraceSettingsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "trace_until", nullable = false)
    private Instant traceUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Instant getTraceUntil() {
        return traceUntil;
    }

    public void setTraceUntil(Instant traceUntil) {
        this.traceUntil = traceUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
