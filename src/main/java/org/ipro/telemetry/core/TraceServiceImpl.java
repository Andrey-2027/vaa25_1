package org.ipro.telemetry.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.ipro.telemetry.api.TraceService;
import org.ipro.telemetry.api.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Реализация {@link TraceService}: окна в trace_settings (JdbcTemplate),
 * кэш активных окон в памяти (проверка при каждом запросе не ходит в БД).
 * Истёкшие окна вычищаются лениво при чтении.
 */
public final class TraceServiceImpl implements TraceService {

    private static final Logger log = LoggerFactory.getLogger("ipro.telemetry.trace");

    private static final String SELECT_SQL =
            "SELECT user_id, trace_until FROM trace_settings";
    private static final String INSERT_SQL =
            "INSERT INTO trace_settings (user_id, trace_until, created_at, created_by) VALUES (?, ?, ?, ?)";
    private static final String DELETE_SQL =
            "DELETE FROM trace_settings WHERE user_id = ?";

    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<String, Instant> active = new ConcurrentHashMap<>();

    public TraceServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Загрузить пережившие рестарт окна (до runner'ов приложения). */
    @EventListener
    public void loadFromDatabase(ContextRefreshedEvent event) {
        Instant now = Instant.now();
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT_SQL);
        for (Map<String, Object> row : rows) {
            String userId = (String) row.get("user_id");
            Instant until = ((Timestamp) row.get("trace_until")).toInstant();
            if (userId != null && until.isAfter(now)) {
                active.put(userId, until);
            }
        }
        if (!active.isEmpty()) {
            log.info("loaded {} active trace window(s) from database", active.size());
        }
    }

    @Override
    public void startTrace(String userId, int minutes) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        Instant until = Instant.now().plus(minutes, ChronoUnit.MINUTES);
        jdbc.update(INSERT_SQL, userId, Timestamp.from(until),
                Timestamp.from(Instant.now()), UserContext.defaultInstance().currentUsername());
        active.put(userId, until);
        log.info("trace window started for '{}' until {}", userId, until);
    }

    @Override
    public void startTraceForAll(int minutes) {
        startTrace(ALL_USERS, minutes);
    }

    @Override
    public void stopTrace(String userId) {
        if (userId == null) {
            return;
        }
        jdbc.update(DELETE_SQL, userId);
        active.remove(userId);
        log.info("trace window stopped for '{}'", userId);
    }

    @Override
    public void stopAllTraces() {
        jdbc.update("DELETE FROM trace_settings");
        active.clear();
        log.info("all trace windows stopped");
    }

    @Override
    public boolean isTraceActive(String userId) {
        Instant now = Instant.now();
        return isActive(userId, now) || isActive(ALL_USERS, now);
    }

    private boolean isActive(String key, Instant now) {
        if (key == null) {
            return false;
        }
        Instant until = active.get(key);
        if (until == null) {
            return false;
        }
        if (until.isAfter(now)) {
            return true;
        }
        active.remove(key, until);
        return false;
    }

    @Override
    public List<TraceWindow> activeTraces() {
        Instant now = Instant.now();
        List<TraceWindow> result = new ArrayList<>();
        for (Map.Entry<String, Instant> entry : active.entrySet()) {
            if (isActive(entry.getKey(), now)) {
                result.add(new TraceWindow(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }
}