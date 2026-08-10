package org.ipro.telemetry.core;

import java.util.Map;

import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.api.Telemetry;
import org.springframework.stereotype.Component;

/**
 * Реализация фасада телеметрии поверх {@link OperationContext}.
 * Операция стартует только при пустом стеке (первый вход побеждает);
 * вложенные вызовы внутри активной операции не порождают новых операций.
 */
@Component
public class TelemetryService implements Telemetry {

    private final OperationContext operationContext;

    public TelemetryService(OperationContext operationContext) {
        this.operationContext = operationContext;
    }

    @Override
    public OperationScope beginOperation(String name) {
        return operationContext.beginOperation(name);
    }

    @Override
    public OperationScope beginOperation(String name, Map<String, String> context) {
        OperationScope scope = operationContext.beginOperation(name);
        if (context != null) {
            context.forEach(operationContext::putContext);
        }
        return scope;
    }

    @Override
    public void context(Map<String, String> values) {
        if (values != null) {
            values.forEach(operationContext::putContext);
        }
    }
}