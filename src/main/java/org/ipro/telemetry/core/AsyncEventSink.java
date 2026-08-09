package org.ipro.telemetry.core;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.ipro.telemetry.api.AggregateStats;
import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.FieldChangeRecord;
import org.ipro.telemetry.api.TelemetryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Реализация {@link EventSink}: bounded очередь + writer-поток с батчевым
 * JDBC-персистом в operation_log / perf_stats.
 * <p>
 * {@link #accept}/{@link #acceptStats} — fire-and-forget: offer() без блокировки,
 * при переполнении событие дропается (счётчик + WARN с троттлингом).
 * {@link #acceptDurable} — синхронная запись с подтверждением (SECURITY).
 * <p>
 * Self-observation: writer ловит все исключения, считает упавшие батчи и
 * держит последнюю ошибку — состояние видно через {@link #getState()}.
 */
public final class AsyncEventSink implements EventSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger("ipro.telemetry.sink");
    private static final int BATCH_SIZE = 100;
    private static final long DROP_WARN_INTERVAL_MS = 30_000;

    private static final String EVENT_SQL = """
            INSERT INTO operation_log
                (event_type, level, started_at, duration_ms, trace_id, user_id, session_id,
                 operation, entity, entity_id, sql_count, sql_total_ms, n1, error_message, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String STATS_SQL = """
            INSERT INTO perf_stats
                (stat_key, window_start, count, total_ms, avg_ms, min_ms, max_ms, p95_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String CHANGE_SQL = """
            INSERT INTO entity_change_log
                (changed_at, change_type, entity, entity_id, user_id, trace_id, field_count, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """;

    private sealed interface Entry permits EventEntry, StatsEntry, FieldAuditEntry {
    }

    private record EventEntry(TelemetryEvent event) implements Entry {
    }

    private record StatsEntry(AggregateStats stats) implements Entry {
    }

    private record FieldAuditEntry(FieldChangeRecord change) implements Entry {
    }

    private final BlockingQueue<Entry> queue;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Thread thread;
    private volatile boolean running = true;

    private final LongAdder writtenEvents = new LongAdder();
    private final LongAdder writtenStats = new LongAdder();
    private final LongAdder writtenFieldChanges = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder failedBatches = new LongAdder();
    private final AtomicLong lastDropWarnAt = new AtomicLong();
    private volatile String lastError;

    public AsyncEventSink(JdbcTemplate jdbc, PlatformTransactionManager txManager, int queueSize) {
        this.queue = new LinkedBlockingQueue<>(Math.max(64, queueSize));
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.thread = new Thread(this::run, "telemetry-event-sink");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @Override
    public void accept(TelemetryEvent event) {
        if (event == null) {
            return;
        }
        if (!queue.offer(new EventEntry(event))) {
            dropped.increment();
            warnAboutDrops();
        }
    }

    @Override
    public void acceptDurable(TelemetryEvent event) {
        if (event == null) {
            return;
        }
        TelemetryGuard.insideLogging(() -> {
            try {
                tx.executeWithoutResult(status -> jdbc.update(EVENT_SQL, eventArgs(event)));
                writtenEvents.increment();
            } catch (RuntimeException e) {
                failedBatches.increment();
                lastError = e.toString();
                log.error("durable telemetry event write failed: {}", e.toString());
            }
        });
    }

    @Override
    public void acceptStats(AggregateStats stats) {
        if (stats == null) {
            return;
        }
        if (!queue.offer(new StatsEntry(stats))) {
            dropped.increment();
            warnAboutDrops();
        }
    }

    @Override
    public void acceptFieldChange(FieldChangeRecord change) {
        if (change == null) {
            return;
        }
        if (!queue.offer(new FieldAuditEntry(change))) {
            dropped.increment();
            warnAboutDrops();
        }
    }

    @Override
    public void acceptFieldChangeDurable(FieldChangeRecord change) {
        if (change == null) {
            return;
        }
        TelemetryGuard.insideLogging(() -> {
            try {
                tx.executeWithoutResult(status -> jdbc.update(CHANGE_SQL, changeArgs(change)));
                writtenFieldChanges.increment();
            } catch (RuntimeException e) {
                failedBatches.increment();
                lastError = e.toString();
                log.error("durable field audit write failed: {}", e.toString());
            }
        });
    }

    @Override
    public void flush() {
        List<Entry> pending = new ArrayList<>();
        queue.drainTo(pending);
        if (!pending.isEmpty()) {
            writeEntries(pending);
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush();
    }

    /** Состояние sink для self-observation (UI журнала, этап 9). */
    public SinkState getState() {
        return new SinkState(queue.size(), writtenEvents.sum(), writtenStats.sum(),
                writtenFieldChanges.sum(), dropped.sum(), failedBatches.sum(), lastError);
    }

    public record SinkState(int queueSize, long writtenEvents, long writtenStats,
                            long writtenFieldChanges, long dropped, long failedBatches,
                            String lastError) {
    }

    private void run() {
        while (running) {
            Entry first;
            try {
                first = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (first == null) {
                continue;
            }
            List<Entry> batch = new ArrayList<>(BATCH_SIZE);
            batch.add(first);
            queue.drainTo(batch, BATCH_SIZE - 1);
            writeEntries(batch);
        }
    }

    private void writeEntries(List<Entry> batch) {
        TelemetryGuard.insideLogging(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    List<Object[]> eventRows = new ArrayList<>();
                    List<Object[]> statsRows = new ArrayList<>();
                    List<Object[]> changeRows = new ArrayList<>();
                    for (Entry entry : batch) {
                        switch (entry) {
                            case EventEntry e -> eventRows.add(eventArgs(e.event()));
                            case StatsEntry s -> statsRows.add(statsArgs(s.stats()));
                            case FieldAuditEntry c -> changeRows.add(changeArgs(c.change()));
                        }
                    }
                    if (!eventRows.isEmpty()) {
                        int[] counts = jdbc.batchUpdate(EVENT_SQL, eventRows);
                        writtenEvents.add(counts.length);
                    }
                    if (!statsRows.isEmpty()) {
                        int[] counts = jdbc.batchUpdate(STATS_SQL, statsRows);
                        writtenStats.add(counts.length);
                    }
                    if (!changeRows.isEmpty()) {
                        int[] counts = jdbc.batchUpdate(CHANGE_SQL, changeRows);
                        writtenFieldChanges.add(counts.length);
                    }
                });
            } catch (RuntimeException e) {
                failedBatches.increment();
                lastError = e.toString();
                log.warn("telemetry batch write failed ({} events dropped): {}",
                        batch.size(), e.toString());
            }
        });
    }

    private Object[] eventArgs(TelemetryEvent event) {
        return new Object[]{
                event.type().name(),
                event.level(),
                event.startedAt() == null ? null : Timestamp.from(event.startedAt()),
                event.durationMs(),
                event.traceId(),
                event.userId(),
                event.sessionId(),
                event.operation(),
                event.entity(),
                event.entityId(),
                event.sqlCount(),
                event.sqlTotalMs() == null ? null : event.sqlTotalMs().doubleValue(),
                event.n1(),
                event.errorMessage(),
                event.payload()
        };
    }

    private Object[] statsArgs(AggregateStats stats) {
        return new Object[]{
                stats.key(),
                Timestamp.from(stats.windowStart()),
                stats.count(),
                stats.totalMs(),
                stats.avgMs(),
                stats.minMs(),
                stats.maxMs(),
                stats.p95Ms()
        };
    }

    private Object[] changeArgs(FieldChangeRecord change) {
        return new Object[]{
                Timestamp.from(change.changedAt()),
                change.changeType(),
                change.entity(),
                change.entityId(),
                change.userId(),
                change.traceId(),
                change.fieldCount(),
                change.payload()
        };
    }

    private void warnAboutDrops() {
        long now = System.currentTimeMillis();
        long last = lastDropWarnAt.get();
        if (now - last >= DROP_WARN_INTERVAL_MS
                && lastDropWarnAt.compareAndSet(last, now)) {
            log.warn("telemetry queue full ({}), dropped {} events total",
                    queue.size(), dropped.sum());
        }
    }
}
