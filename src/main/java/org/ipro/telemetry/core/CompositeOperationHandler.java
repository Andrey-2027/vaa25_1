package org.ipro.telemetry.core;

import java.util.List;

/**
 * Составной обработчик завершения операций: вызывает все регистрированные
 * обработчики по порядку (SlowOperationHandler — аномалии/журнал действий,
 * TraceDumpHandler — L2-трассы).
 */
public final class CompositeOperationHandler implements OperationCompletionHandler {

    private final List<OperationCompletionHandler> handlers;

    public CompositeOperationHandler(List<OperationCompletionHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    @Override
    public void onOperationComplete(Operation operation) {
        for (OperationCompletionHandler handler : handlers) {
            handler.onOperationComplete(operation);
        }
    }
}