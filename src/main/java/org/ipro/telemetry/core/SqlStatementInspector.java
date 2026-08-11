package org.ipro.telemetry.core;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.ipro.rls.RlsStatementGuard;

/**
 * StatementInspector (свойство {@code hibernate.session_factory.statement_inspector}):
 * захватывает SQL-текст на подготовке стейтмента и ведёт счёт нормализованных
 * запросов в текущей операции для N+1-детекции.
 * <p>
 * Используется ТОЛЬКО для подсчёта (текст, не время) — тайминг даёт
 * {@link SqlStatementListener}. Регистрируется самим Hibernate, поэтому
 * public no-arg конструктор и доступ к контексту через {@link SqlTimingBridge}.
 * <p>
 * Заодно прогоняет каждый SQL через RLS-канарейку {@link RlsStatementGuard} —
 * композиция, а не второй StatementInspector (Hibernate держит только один).
 * Guard работает независимо от телеметрии и вызывается ДО её early-return'ов.
 */
public final class SqlStatementInspector implements StatementInspector {

    @Override
    public String inspect(String sql) {
        if (sql == null) {
            return null;
        }
        RlsStatementGuard.inspect(sql);
        if (!TelemetryGuard.isEnabled() || TelemetryGuard.isInsideLogging()) {
            return sql;
        }
        Operation operation = SqlTimingBridge.currentOperation();
        if (operation != null) {
            operation.countSql(SqlNormalizer.normalize(sql));
            if (operation.isTraceActive()) {
                SqlTimingBridge.setLastStatement(sql);
            }
        }
        return sql;
    }
}
