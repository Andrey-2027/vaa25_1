package org.ipro.telemetry.api;

import java.time.Instant;

/**
 * Снапшот L0-агрегата счётчика (метод / нормализованный SQL) за окно.
 * Передаётся в {@link EventSink#acceptStats} для персиста в perf_stats.
 */
public record AggregateStats(
        String key,
        long count,
        double totalMs,
        double avgMs,
        double minMs,
        double maxMs,
        double p95Ms,
        Instant windowStart) {
}
