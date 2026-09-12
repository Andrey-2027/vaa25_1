package org.ipro.telemetry.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.FieldChangeRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Проверяет H2-совместимость PostgreSQL-контракта field audit.
 *
 * <p>Hibernate выбирает {@code jsonb} для PostgreSQL и {@code json} для H2,
 * а {@link EventSink} — соответствующий синтаксис JDBC-параметра. Валидность и
 * PostgreSQL JSONB operators этот тест намеренно не имитирует.</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
class EntityChangeLogH2CompatibilityIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EventSink eventSink;

    @Test
    @Transactional
    void createsAuditTableAndPreservesJsonPayload() {
        String payload = "[{\"field\":\"name\",\"old\":\"before\",\"new\":\"after\"}]";

        eventSink.acceptFieldChangeDurable(new FieldChangeRecord(
                Instant.parse("2026-09-11T00:00:00Z"),
                "UPDATE", "CompatibilityProbe", "1",
                "test", "jsonb-test", 1, payload));

        String stored = jdbc.queryForObject("""
                SELECT payload
                FROM entity_change_log
                WHERE trace_id = ?
                """, String.class, "jsonb-test");

        assertThat(stored).isEqualTo(payload);
    }
}
