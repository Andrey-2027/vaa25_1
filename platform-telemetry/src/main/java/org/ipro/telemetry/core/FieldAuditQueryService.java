package org.ipro.telemetry.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Read-only доступ к журналу изменений полей (entity_change_log) для UI
 * (этап 10): строки с фильтрами, payload по id (список изменённых полей).
 * <p>
 * Доступ — только ROLE_ADMIN (та же политика, что у JournalQueryService):
 * payload содержит чувствительные значения полей.
 */
public final class FieldAuditQueryService {

    private static final String CHANGE_COLUMNS =
            "id, changed_at, change_type, entity, entity_id, user_id, trace_id, field_count";

    private final JdbcTemplate jdbc;

    public FieldAuditQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Фильтр выборки журнала изменений (все условия необязательны). */
    public record ChangeFilter(
            String entity,
            String entityId,
            String userId,
            Instant from,
            Instant to,
            int limit) {
    }

    /** Строка журнала изменений (без payload — он берётся по id). */
    public record ChangeRow(
            long id,
            Instant changedAt,
            String changeType,
            String entity,
            String entityId,
            String userId,
            String traceId,
            Integer fieldCount) {
    }

    public List<ChangeRow> queryChanges(ChangeFilter filter) {
        requireAdmin();
        StringBuilder sql = new StringBuilder("SELECT " + CHANGE_COLUMNS
                + " FROM entity_change_log WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (filter.entity() != null && !filter.entity().isBlank()) {
            sql.append(" AND entity = ?");
            params.add(filter.entity().trim());
        }
        if (filter.entityId() != null && !filter.entityId().isBlank()) {
            sql.append(" AND entity_id = ?");
            params.add(filter.entityId().trim());
        }
        if (filter.userId() != null && !filter.userId().isBlank()) {
            sql.append(" AND user_id ILIKE ?");
            params.add("%" + filter.userId().trim() + "%");
        }
        if (filter.from() != null) {
            sql.append(" AND changed_at >= ?");
            params.add(java.sql.Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND changed_at < ?");
            params.add(java.sql.Timestamp.from(filter.to()));
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(filter.limit() <= 0 ? 500 : Math.min(filter.limit(), 2000));
        return jdbc.query(sql.toString(), (rs, rowNum) -> mapRow(rs), params.toArray());
    }

    /** payload_json записи (массив изменённых полей) или null. */
    public String payloadById(long id) {
        requireAdmin();
        List<String> payloads = jdbc.query(
                "SELECT payload FROM entity_change_log WHERE id = ?",
                (rs, rowNum) -> rs.getString(1), id);
        return payloads.isEmpty() ? null : payloads.get(0);
    }

    private void requireAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = auth != null && auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
        if (!admin) {
            throw new AccessDeniedException("field audit access requires ROLE_ADMIN");
        }
    }

    private ChangeRow mapRow(ResultSet rs) throws SQLException {
        return new ChangeRow(
                rs.getLong("id"),
                rs.getTimestamp("changed_at") == null ? null : rs.getTimestamp("changed_at").toInstant(),
                rs.getString("change_type"),
                rs.getString("entity"),
                rs.getString("entity_id"),
                rs.getString("user_id"),
                rs.getString("trace_id"),
                rs.getObject("field_count") == null ? null : rs.getInt("field_count"));
    }
}
