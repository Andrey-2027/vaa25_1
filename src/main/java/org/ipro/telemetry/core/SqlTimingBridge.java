package org.ipro.telemetry.core;

/**
 * Мост между классами, инстанцируемыми Hibernate (SessionEventListener,
 * StatementInspector — только public no-arg конструктор, без Spring),
 * и бином {@link OperationContext}. Статическое поле выставляется
 * авто-конфигурацией при старте.
 */
public final class SqlTimingBridge {

    private static volatile OperationContext operationContext;

    /**
     * Последний SQL-текст на потоке: кладёт StatementInspector (на этапе
     * prepare), забирает SqlStatementListener (на jdbcExecuteStatementEnd)
     * для привязки к фрейму при активной трассе.
     */
    private static final ThreadLocal<String> lastStatement = new ThreadLocal<>();

    private SqlTimingBridge() {
    }

    public static void setOperationContext(OperationContext context) {
        operationContext = context;
    }

    public static OperationContext operationContext() {
        return operationContext;
    }

    public static Operation currentOperation() {
        OperationContext context = operationContext;
        return context == null ? null : context.currentOperation();
    }

    public static Frame currentFrame() {
        OperationContext context = operationContext;
        return context == null ? null : context.currentFrame();
    }

    public static void setLastStatement(String sql) {
        lastStatement.set(sql);
    }

    /** Забрать SQL-текст последнего подготовленного стейтмента (и очистить). */
    public static String takeLastStatement() {
        String sql = lastStatement.get();
        lastStatement.remove();
        return sql;
    }
}
