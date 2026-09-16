package org.ipro.telemetry.config;

import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.api.Telemetry;
import org.ipro.telemetry.core.MdcKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Self-test field-level аудита (включается ipro.telemetry.field-audit-self-test=true):
 * сохраняет тестовую запись с заведомым изменением поля в отдельной операции,
 * затем проверяет появление строки в entity_change_log с верным diff.
 * <p>
 * Проверяет end-to-end цепочку: Pre*EventListener → аккумулятор операции →
 * FieldAuditOperationHandler → durable-запись в entity_change_log.
 * Чтение — напрямую через JdbcTemplate (без admin-проверки сервиса:
 * при старте приложения security-контекста ещё нет).
 */
public class FieldAuditSelfTest implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger("ipro.telemetry.field-audit");

    private final jakarta.persistence.EntityManagerFactory entityManagerFactory;
    private final TransactionTemplate tx;
    private final Telemetry telemetry;
    private final JdbcTemplate jdbc;

    public FieldAuditSelfTest(jakarta.persistence.EntityManagerFactory entityManagerFactory,
                              PlatformTransactionManager transactionManager,
                              Telemetry telemetry,
                              JdbcTemplate jdbc) {
        this.entityManagerFactory = entityManagerFactory;
        this.tx = new TransactionTemplate(transactionManager);
        this.telemetry = telemetry;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            runTest();
        } catch (Exception e) {
            log.error("field-audit self-test FAILED: {}", e.toString());
        }
    }

    private void runTest() {
        MDC.put(MdcKeys.USER, "system");
        String key = "selftest.field-audit." + System.currentTimeMillis();
        Object id;
        try (OperationScope scope = telemetry.beginOperation("selftest:field-audit")) {
            id = tx.execute(status -> {
                jakarta.persistence.EntityManager em = entityManagerFactory.createEntityManager();
                try {
                    em.joinTransaction();
                    // События PreInsert/PreUpdate срабатывают на persist/flush
                    // (native SQL их НЕ порождает), поэтому тест идёт через entity API.
                    // Сущность — платформенная (SettingValue): self-test не зависит
                    // от домена приложения.
                    org.ipro.settings.SettingValue value =
                        new org.ipro.settings.SettingValue(key, "GLOBAL", 0L);
                    value.setStringValue("before");
                    em.persist(value);
                    em.flush();
                    value.setStringValue("after");
                    em.flush();
                    return value.getId();
                } finally {
                    em.close();
                }
            });
        }
        verify(key, id);
        cleanup(key);
        MDC.clear();
    }

    private void verify(String key, Object id) {
        try {
            java.util.List<String> payloads = jdbc.query(
                    "SELECT payload FROM entity_change_log WHERE entity = 'SettingValue' "
                            + "AND entity_id = ? ORDER BY id DESC LIMIT 1",
                    (rs, rowNum) -> rs.getString(1), String.valueOf(id));
            if (payloads.isEmpty()) {
                log.error("field-audit self-test FAILED: no change rows for SettingValue #{}", id);
                return;
            }
            String payload = payloads.get(0);
            boolean hasDiff = payload != null && payload.contains("\"stringValue\"")
                    && payload.contains("\"before\"") && payload.contains("\"after\"");
            if (hasDiff) {
                log.info("field-audit self-test OK: SettingValue #{} change recorded: {}", id, payload);
            } else {
                log.error("field-audit self-test FAILED: unexpected payload for SettingValue #{}: {}",
                        id, payload);
            }
        } catch (RuntimeException e) {
            log.error("field-audit self-test FAILED: query error: {}", e.toString());
        }
    }

    private void cleanup(String key) {
        try {
            int removed = jdbc.update("DELETE FROM setting_value WHERE setting_key = ?", key);
            if (removed > 0) {
                log.info("field-audit self-test: cleaned up {} test row(s)", removed);
            }
        } catch (RuntimeException e) {
            log.warn("field-audit self-test cleanup failed: {}", e.toString());
        }
    }
}
