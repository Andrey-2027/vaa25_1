package org.ipro.telemetry.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.ipro.telemetry.model.OperationLogEntity;
import org.ipro.telemetry.repository.OperationLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Поисковый слой журнала телеметрии для UI «Диагностика»: ленивая выборка
 * operation_log через {@link OperationLogRepository} (Specification +
 * Pageable) и группировка повторяющихся ошибок.
 * <p>
 * Доступ — только ROLE_ADMIN, проверка на уровне сервиса (тот же принцип,
 * что в {@link JournalQueryService}): payload_json содержит потенциально
 * чувствительные данные, одной проверки в UI недостаточно.
 */
public final class JournalSearchService {

    /** Максимальный размер страницы ленивого грида (защита от «всё сразу»). */
    public static final int MAX_PAGE_SIZE = 200;

    private final OperationLogRepository repository;
    private final JdbcTemplate jdbc;

    public JournalSearchService(OperationLogRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ поиск

    /** Ленивая страница журнала; сортировка и смещение приходят из грида. */
    public Page<OperationLogEntity> search(Specification<OperationLogEntity> spec, Pageable pageable) {
        requireAdmin();
        return repository.findAll(spec, pageable);
    }

    // ------------------------------------------------ Specification-хелперы

    public static Specification<OperationLogEntity> eventTypeEq(String eventType) {
        return (root, query, cb) -> eventType == null || eventType.isBlank()
                ? cb.conjunction()
                : cb.equal(root.get("eventType"), eventType);
    }

    public static Specification<OperationLogEntity> levelEq(String level) {
        return (root, query, cb) -> level == null || level.isBlank()
                ? cb.conjunction()
                : cb.equal(root.get("level"), level);
    }

    public static Specification<OperationLogEntity> traceIdEq(String traceId) {
        return (root, query, cb) -> traceId == null || traceId.isBlank()
                ? cb.conjunction()
                : cb.equal(root.get("traceId"), traceId.trim());
    }

    /** Подстрока текста ошибки (без учёта регистра). */
    public static Specification<OperationLogEntity> errorMessageContains(String text) {
        return (root, query, cb) -> text == null || text.isBlank()
                ? cb.conjunction()
                : cb.like(cb.lower(cb.substring(root.get("errorMessage"), 1, 300)),
                        "%" + text.toLowerCase() + "%");
    }

    public static Specification<OperationLogEntity> durationGte(Long minDurationMs) {
        return (root, query, cb) -> minDurationMs == null
                ? cb.conjunction()
                : cb.ge(root.get("durationMs"), minDurationMs.doubleValue());
    }

    /** Нижняя граница периода по started_at (включительно). */
    public static Specification<OperationLogEntity> startedAtGte(Instant from) {
        return (root, query, cb) -> from == null
                ? cb.conjunction()
                : cb.greaterThanOrEqualTo(root.get("startedAt"), from);
    }

    /** Верхняя граница периода по started_at (не включительно). */
    public static Specification<OperationLogEntity> startedAtLte(Instant to) {
        return (root, query, cb) -> to == null
                ? cb.conjunction()
                : cb.lessThan(root.get("startedAt"), to);
    }

    public static Specification<OperationLogEntity> n1True() {
        return (root, query, cb) -> cb.isTrue(root.get("n1"));
    }

    // --------------------------------------------------- группировка ошибок

    /** Отфильтрованная группа повторяющихся ошибок (см. {@link ErrorGroupRow}). */
    public record ErrorGroupFilter(
            String userId,
            String operation,
            Instant from,
            Instant to) {
    }

    /**
     * Одна группа одинаковых ошибок: «отпечаток» = операция + первая строка
     * error_message. Отсортировано по количеству по убыванию.
     */
    public record ErrorGroupRow(
            long count,
            long distinctUsers,
            Instant firstSeen,
            Instant lastSeen,
            String operation,
            String errorFingerprint) {
    }

    private static final String ERROR_GROUP_SQL = """
            SELECT operation,
                   SPLIT_PART(COALESCE(error_message, ''), E'\\n', 1) AS fingerprint,
                   COUNT(*) AS cnt,
                   COUNT(DISTINCT user_id) AS users,
                   MIN(started_at) AS first_seen,
                   MAX(started_at) AS last_seen
            FROM operation_log
            WHERE event_type = 'ERROR' AND level = 'ERROR'
            """;

    /**
     * Группировка одинаковых ошибок: GROUP BY операция + первая строка текста
     * ошибки (SPLIT_PART по переводу строки), с фильтрами по пользователю,
     * операции и периоду. Выкидывает исключение при неожиданном диалекте —
     * приложение целится в PostgreSQL (см. application.properties), а тихо
     * показывать пустую сводку было бы хуже.
     */
    public List<ErrorGroupRow> errorGroups(ErrorGroupFilter filter) {
        requireAdmin();
        String dialect;
        try {
            dialect = String.valueOf(jdbc.getDataSource()).toUpperCase();
        } catch (RuntimeException e) {
            dialect = "";
        }
        if (!dialect.contains("POSTGRES")) {
            throw new IllegalStateException(
                    "Группировка ошибок поддерживает только PostgreSQL, найдено: " + dialect);
        }

        StringBuilder sql = new StringBuilder(ERROR_GROUP_SQL);
        List<Object> params = new ArrayList<>();
        if (filter.userId() != null && !filter.userId().isBlank()) {
            sql.append(" AND user_id ILIKE ?");
            params.add("%" + filter.userId().trim() + "%");
        }
        if (filter.operation() != null && !filter.operation().isBlank()) {
            sql.append(" AND operation ILIKE ?");
            params.add("%" + filter.operation().trim() + "%");
        }
        if (filter.from() != null) {
            sql.append(" AND started_at >= ?");
            params.add(java.sql.Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND started_at < ?");
            params.add(java.sql.Timestamp.from(filter.to()));
        }
        sql.append(" GROUP BY operation, fingerprint"
                + " ORDER BY cnt DESC, last_seen DESC"
                + " LIMIT 500");

        return jdbc.query(sql.toString(), this::mapErrorGroup, params.toArray());
    }

    private ErrorGroupRow mapErrorGroup(ResultSet rs, int rowNum) throws SQLException {
        String operation = rs.getString("operation");
        String fingerprint = rs.getString("fingerprint");
        return new ErrorGroupRow(
                rs.getLong("cnt"),
                rs.getLong("users"),
                toInstant(rs.getTimestamp("first_seen")),
                toInstant(rs.getTimestamp("last_seen")),
                operation,
                fingerprint != null ? fingerprint : "");
    }

    private static Instant toInstant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    // ---------------------------------------------------------------- права

    private void requireAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = auth != null && auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
        if (!admin) {
            throw new AccessDeniedException("journal access requires ROLE_ADMIN");
        }
    }
}
