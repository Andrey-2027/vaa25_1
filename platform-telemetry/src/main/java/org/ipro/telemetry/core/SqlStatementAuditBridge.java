package org.ipro.telemetry.core;

import org.ipro.telemetry.api.SqlStatementAudit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Мост между Hibernate-инспектором стейтментов (public no-arg конструктор, без Spring) и
 * наблюдателями SQL, которые живут в Spring-бинах. Ставится и снимается конфигурацией того,
 * кто наблюдает.
 *
 * <p>Устроен как {@link SqlTimingBridge}, но с двумя отличиями, и оба нужны именно из-за
 * направления зависимостей:</p>
 *
 * <ul>
 *   <li><b>Наблюдателей несколько.</b> Слот один означал бы, что вторая подсистема,
 *       желающая видеть SQL, молча вытесняет первую; список снимает этот вопрос, а пустой
 *       список стоит столько же, сколько раньше стоил {@code null}.</li>
 *   <li><b>Установка адресуется наружу.</b> Реализации приходят от верхних слоёв (RLS —
 *       канарейка «тихих утечек»), поэтому мост лежит в телеметрии, а ставит в него себя
 *       тот, кого наблюдают.</li>
 * </ul>
 *
 * <p>Снятие адресует конкретного наблюдателя, а не очищает список: тесты живут в одной JVM
 * и делят статику, поэтому закрытие соседнего контекста не должно обезоруживать ещё живой —
 * та же причина, по которой регистрации {@code InstanceNameBridge} именованы.</p>
 */
public final class SqlStatementAuditBridge {

    private static final List<SqlStatementAudit> AUDITS = new CopyOnWriteArrayList<>();

    private SqlStatementAuditBridge() {
    }

    public static void install(SqlStatementAudit audit) {
        AUDITS.add(audit);
    }

    public static void uninstall(SqlStatementAudit audit) {
        AUDITS.remove(audit);
    }

    /** Вызывается инспектором на каждый SQL-текст. Пустой список — no-op. */
    public static void audit(String sql) {
        if (AUDITS.isEmpty()) {
            return;
        }
        for (SqlStatementAudit audit : AUDITS) {
            audit.audit(sql);
        }
    }

    /** Для диагностики и тестов: сколько наблюдателей сейчас установлено. */
    public static int installedCount() {
        return AUDITS.size();
    }
}
