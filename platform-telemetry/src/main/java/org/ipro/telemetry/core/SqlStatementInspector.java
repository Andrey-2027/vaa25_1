package org.ipro.telemetry.core;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * StatementInspector (свойство {@code hibernate.session_factory.statement_inspector}):
 * захватывает SQL-текст на подготовке стейтмента и ведёт счёт нормализованных
 * запросов в текущей операции для N+1-детекции.
 * <p>
 * Используется ТОЛЬКО для подсчёта (текст, не время) — тайминг даёт
 * {@link SqlStatementListener}. Регистрируется самим Hibernate, поэтому
 * public no-arg конструктор и доступ к контексту через {@link SqlTimingBridge}.
 * <p>
 * Заодно прогоняет каждый SQL через наблюдателей {@link SqlStatementAuditBridge} —
 * композиция, а не второй StatementInspector (Hibernate держит только один).
 * Наблюдатели работают независимо от телеметрии и вызываются ДО её early-return'ов.
 *
 * <p>D2 → D3 (пара `telemetry` + `rls`): раньше здесь стоял прямой вызов
 * {@code org.ipro.rls.RlsStatementGuard.inspect} — слой наблюдения не собирался без слоя
 * принуждения. Теперь конкретный наблюдатель приходит извне через мост
 * {@link SqlStatementAuditBridge}, а телеметрия о RLS не знает.</p>
 */
public final class SqlStatementInspector implements StatementInspector {

    @Override
    public String inspect(String sql) {
        if (sql == null) {
            return null;
        }
        SqlStatementAuditBridge.audit(sql);
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
