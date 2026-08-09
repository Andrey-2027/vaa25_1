package org.ipro.telemetry.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.ipro.telemetry.api.EventSink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Read-only доступ к журналу телеметрии для UI (этап 9): события
 * operation_log с фильтрами, payload по id, агрегаты perf_stats,
 * состояние sink (self-observation). Пакет org.ipro.telemetry — UI
 * не ходит в БД напрямую, только через этот сервис.
 * <p>
 * Доступ — только ROLE_ADMIN: проверка на уровне сервиса (а не только
 * в UI-классе), т.к. payload_json содержит потенциально чувствительные
 * данные (в т.ч. снимки сущностей). Вызов из не-админского контекста
 * завершается AccessDeniedException.
 */
public final class JournalQueryService {

    private static final String EVENT_COLUMNS =
            "id, event_type, level, started_at, duration_ms, trace_id, user_id, operation,"
                    + " entity, entity_id, sql_count, sql_total_ms, n1, error_message";

    private final JdbcTemplate jdbc;

    public JournalQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Фильтр выборки событий журнала (все условия необязательны). */
    public record EventFilter(
            String eventType,
            String userId,
            String operation,
            String entity,
            Instant from,
            Instant to,
            Long minDurationMs,
            boolean n1Only,
            int limit) {
    }

    /** Строка журнала (без payload — он тяжёлый, берётся по id). */
    public record EventRow(
            long id,
            String eventType,
            String level,
            Instant startedAt,
            Long durationMs,
            String traceId,
            String userId,
            String operation,
            String entity,
            String entityId,
            Integer sqlCount,
            Long sqlTotalMs,
            boolean n1,
            String errorMessage) {
    }

    /** Агрегат L0 из perf_stats. */
    public record AggRow(
            String statKey,
            Instant windowStart,
            long count,
            double totalMs,
            double avgMs,
            double minMs,
            double maxMs,
            double p95Ms) {
    }

    /** Состояние async-sink (самонаблюдение). */
    public record SinkHealth(
            boolean active,
            int queueSize,
            long writtenEvents,
            long writtenStats,
            long writtenFieldChanges,
            long dropped,
            long failedBatches,
            String lastError) {
    }

    public List<EventRow> queryEvents(EventFilter filter) {
        requireAdmin();
        StringBuilder sql = new StringBuilder("SELECT " + EVENT_COLUMNS
                + " FROM operation_log WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (filter.eventType() != null && !filter.eventType().isBlank()) {
            sql.append(" AND event_type = ?");
            params.add(filter.eventType());
        }
        if (filter.userId() != null && !filter.userId().isBlank()) {
            sql.append(" AND user_id ILIKE ?");
            params.add("%" + filter.userId() + "%");
        }
        if (filter.operation() != null && !filter.operation().isBlank()) {
            sql.append(" AND operation ILIKE ?");
            params.add("%" + filter.operation() + "%");
        }
        if (filter.entity() != null && !filter.entity().isBlank()) {
            sql.append(" AND entity ILIKE ?");
            params.add("%" + filter.entity() + "%");
        }
        if (filter.from() != null) {
            sql.append(" AND started_at >= ?");
            params.add(java.sql.Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND started_at < ?");
            params.add(java.sql.Timestamp.from(filter.to()));
        }
        if (filter.minDurationMs() != null) {
            sql.append(" AND duration_ms >= ?");
            params.add(filter.minDurationMs().doubleValue());
        }
        if (filter.n1Only()) {
            sql.append(" AND n1 = true");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(filter.limit() <= 0 ? 500 : Math.min(filter.limit(), 2000));
        return jdbc.query(sql.toString(), (rs, rowNum) -> mapEvent(rs), params.toArray());
    }

    /** payload_json события (дерево фреймов / SQL) или null. */
    public String payloadById(long id) {
        requireAdmin();
        List<String> payloads = jdbc.query(
                "SELECT payload FROM operation_log WHERE id = ?",
                (rs, rowNum) -> rs.getString(1), id);
        return payloads.isEmpty() ? null : payloads.get(0);
    }

    /** Агрегаты perf_stats: scope "method" | "sql" | null (все), по total_ms DESC. */
    public List<AggRow> aggregates(String scope, Instant from, int limit) {
        requireAdmin();
        StringBuilder sql = new StringBuilder(
                "SELECT stat_key, window_start, count, total_ms, avg_ms, min_ms, max_ms, p95_ms"
                        + " FROM perf_stats WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (scope != null && !scope.isBlank()) {
            sql.append(" AND stat_key LIKE ?");
            params.add(scope + "%");
        }
        if (from != null) {
            sql.append(" AND window_start >= ?");
            params.add(java.sql.Timestamp.from(from));
        }
        sql.append(" ORDER BY total_ms DESC LIMIT ?");
        params.add(limit <= 0 ? 300 : Math.min(limit, 2000));
        return jdbc.query(sql.toString(), (rs, rowNum) -> new AggRow(
                rs.getString("stat_key"),
                rs.getTimestamp("window_start") == null ? null : rs.getTimestamp("window_start").toInstant(),
                rs.getLong("count"),
                rs.getDouble("total_ms"),
                rs.getDouble("avg_ms"),
                rs.getDouble("min_ms"),
                rs.getDouble("max_ms"),
                rs.getDouble("p95_ms")), params.toArray());
    }

    /** Состояние async-writer'а (жив ли, дропы, ошибки) — через TelemetryBridge. */
    public SinkHealth sinkHealth() {
        requireAdmin();
        EventSink sink = TelemetryBridge.getSink();
        if (sink instanceof AsyncEventSink async) {
            AsyncEventSink.SinkState state = async.getState();
            return new SinkHealth(true, state.queueSize(), state.writtenEvents(),
                    state.writtenStats(), state.writtenFieldChanges(), state.dropped(),
                    state.failedBatches(), state.lastError());
        }
        return new SinkHealth(sink != null && !sink.isNoop(), 0, 0, 0, 0, 0, 0, null);
    }

    private void requireAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = auth != null && auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
        if (!admin) {
            throw new AccessDeniedException("journal access requires ROLE_ADMIN");
        }
    }

    private EventRow mapEvent(ResultSet rs) throws SQLException {
        return new EventRow(
                rs.getLong("id"),
                rs.getString("event_type"),
                rs.getString("level"),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getObject("duration_ms") == null ? null : Math.round(rs.getDouble("duration_ms")),
                rs.getString("trace_id"),
                rs.getString("user_id"),
                rs.getString("operation"),
                rs.getString("entity"),
                rs.getString("entity_id"),
                rs.getObject("sql_count") == null ? null : rs.getInt("sql_count"),
                rs.getObject("sql_total_ms") == null ? null : Math.round(rs.getDouble("sql_total_ms")),
                rs.getBoolean("n1"),
                rs.getString("error_message"));
    }
}
