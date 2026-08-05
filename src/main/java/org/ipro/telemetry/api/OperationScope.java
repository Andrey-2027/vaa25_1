package org.ipro.telemetry.api;

/**
 * Автозакрываемый дескриптор операции. Использование:
 * <pre>
 * try (OperationScope scope = telemetry.beginOperation("save:Nomenclature")) {
 *     ...
 * } catch (Throwable t) {
 *     scope.fail(t);
 *     throw t;
 * }
 * </pre>
 * close() не получает Throwable, поэтому ошибка вручную фиксируется через
 * {@link #fail(Throwable)} из catch-блока до закрытия.
 */
public interface OperationScope extends AutoCloseable {

    /** Завершить операцию (при необходимости) и очистить MDC. */
    @Override
    void close();

    /** Зафиксировать сбой операции; вызывается из catch до close(). */
    void fail(Throwable t);

    /** true — операция была реально создана данным вызовом; false — no-op. */
    boolean isActive();

    /** Пустой скоуп (телеметрия выключена/мост не инициализирован). */
    static OperationScope noop() {
        return new OperationScope() {
            @Override
            public void close() {
            }

            @Override
            public void fail(Throwable t) {
            }

            @Override
            public boolean isActive() {
                return false;
            }
        };
    }
}
