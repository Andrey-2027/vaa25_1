package org.ipro.telemetry.core;

/**
 * Вызывается при завершении операции (корневого фрейма).
 * Решает, куда детализировать результат: агрегаты, медленность,
 * событие в журнал.
 */
public interface OperationCompletionHandler {

    void onOperationComplete(Operation operation);
}