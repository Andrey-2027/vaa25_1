package org.ipro.telemetry.core;

/**
 * Мост между классами, инстанцируемыми Hibernate (SessionEventListener,
 * StatementInspector — только public no-arg конструктор, без Spring),
 * и бином {@link OperationContext}. Статическое поле выставляется
 * авто-конфигурацией при старте.
 */
public final class SqlTimingBridge {

    private static volatile OperationContext operationContext;

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
}
