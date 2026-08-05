package org.ipro.telemetry.api;

/**
 * Автозакрываемый дескриптор операции. Использование:
 * <pre>
 * try (OperationScope scope = telemetry.beginOperation("save:Nomenclature")) {
 *     ...
 * }
 * </pre>
 */
public interface OperationScope extends AutoCloseable {

    /** Завершить операцию (при необходимости) и очистить MDC. */
    @Override
    void close();

    /** true — операция была реально создана данным вызовом; false — no-op. */
    boolean isActive();
}
