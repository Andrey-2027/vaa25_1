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
 * L0-агрегаты за окно (метод / нормализованный SQL): count, total, avg,
 * min, max, p95. Строка = один снапшот одного счётчика за окно.
 */
@Entity
@Table(name = "perf_stats",
        indexes = {
                @Index(name = "idx_perf_stats_key", columnList = "stat_key"),
                @Index(name = "idx_perf_stats_window_start", columnList = "window_start")
        })
public class PerfStatsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stat_key", nullable = false, length = 255)
    private String key;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(nullable = false)
    private long count;

    @Column(name = "total_ms")
    private double totalMs;

    @Column(name = "avg_ms")
    private double avgMs;

    @Column(name = "min_ms")
    private double minMs;

    @Column(name = "max_ms")
    private double maxMs;

    @Column(name = "p95_ms")
    private double p95Ms;

    public Long getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(Instant windowStart) {
        this.windowStart = windowStart;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public double getTotalMs() {
        return totalMs;
    }

    public void setTotalMs(double totalMs) {
        this.totalMs = totalMs;
    }

    public double getAvgMs() {
        return avgMs;
    }

    public void setAvgMs(double avgMs) {
        this.avgMs = avgMs;
    }

    public double getMinMs() {
        return minMs;
    }

    public void setMinMs(double minMs) {
        this.minMs = minMs;
    }

    public double getMaxMs() {
        return maxMs;
    }

    public void setMaxMs(double maxMs) {
        this.maxMs = maxMs;
    }

    public double getP95Ms() {
        return p95Ms;
    }

    public void setP95Ms(double p95Ms) {
        this.p95Ms = p95Ms;
    }
}
