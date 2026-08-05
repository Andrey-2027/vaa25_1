package org.ipro.telemetry.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Retention (этап 8): ежедневная очистка данных телеметрии по срокам
 * хранения. Удаляет из operation_log события (кроме SECURITY — 90 дней,
 * SECURITY — 1 год), агрегаты perf_stats (1 год) и старые trace-файлы.
 * <p>
 * Выполняется на планировщике (не UI/сервисный поток, MDC пуст, попадания в AOP
 * сервисного слоя нет); БД-операции обёрнуты в guard от рекурсии и батчатся
 * по 500 строк, защита от параллельного запуска — AtomicBoolean.
 */
public class RetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger("ipro.telemetry.retention");
    private static final int EVENT_BATCH = 500;
    private static final long MAX_EVENT_ROWS = 5_000_000;

    private final JdbcTemplate jdbc;
    private final Path traceDir;
    private final int eventsDays;
    private final int securityDays;
    private final int statsDays;
    private final int traceHours;
    private final AtomicBoolean running = new AtomicBoolean();

    public RetentionPurgeJob(JdbcTemplate jdbc, String traceDir,
                             int eventsDays, int securityDays, int statsDays, int traceHours) {
        this.jdbc = jdbc;
        this.traceDir = traceDir == null || traceDir.isBlank() ? null : Paths.get(traceDir);
        this.eventsDays = eventsDays;
        this.securityDays = securityDays;
        this.statsDays = statsDays;
        this.traceHours = traceHours;
    }

    @Scheduled(cron = "${ipro.telemetry.retention.cron:0 0 3 * * *}")
    public void purge() {
        if (!running.compareAndSet(false, true)) {
            log.warn("retention run skipped: previous purge still active");
            return;
        }
        try {
            purgeEvents(securityDays, true);
            purgeEvents(eventsDays, false);
            purgeStats(statsDays);
            purgeTraceFiles(traceHours);
        } catch (RuntimeException e) {
            log.error("retention job failed: {}", e.toString());
        } finally {
            running.set(false);
        }
    }

    private void purgeEvents(int days, boolean security) {
        if (days <= 0) {
            return;
        }
        Timestamp before = Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS));
        String typeCond = security
                ? "event_type = 'SECURITY'"
                : "event_type <> 'SECURITY'";
        String sql = "DELETE FROM operation_log WHERE id IN (SELECT id FROM operation_log WHERE "
                + typeCond + " AND started_at < ? ORDER BY id LIMIT " + EVENT_BATCH + ")";
        int total = 0;
        int batch;
        do {
            final int[] removed = {0};
            TelemetryGuard.insideLogging(() -> removed[0] = jdbc.update(sql, before));
            batch = removed[0];
            total += batch;
        } while (batch > 0 && total < MAX_EVENT_ROWS);
        if (total > 0) {
            log.info("purge: removed {} operation_log rows (older than {} days{})",
                    total, days, security ? ", SECURITY" : "");
        }
    }

    private void purgeStats(int days) {
        if (days <= 0) {
            return;
        }
        Timestamp before = Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS));
        final int[] removed = {0};
        TelemetryGuard.insideLogging(() -> removed[0] = jdbc.update(
                "DELETE FROM perf_stats WHERE window_start < ?", before));
        if (removed[0] > 0) {
            log.info("purge: removed {} perf_stats rows (older than {} days)",
                    removed[0], days);
        }
    }

    private void purgeTraceFiles(int hours) {
        if (traceDir == null || hours <= 0 || !Files.exists(traceDir)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - (long) hours * 3600_000L;
        final int[] removed = {0};
        try {
            Files.walkFileTree(traceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.startsWith("trace_") && name.endsWith(".txt")
                            && attrs.lastModifiedTime().toMillis() < cutoff) {
                        try {
                            Files.deleteIfExists(file);
                            removed[0]++;
                        } catch (IOException e) {
                            log.warn("purge: cannot delete trace file {}: {}", file, e.toString());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            if (removed[0] > 0) {
                log.info("purge: removed {} trace files (older than {}h)",
                        removed[0], hours);
            }
        } catch (IOException e) {
            log.warn("purge: trace-dir scan failed: {}", e.toString());
        }
    }
}
