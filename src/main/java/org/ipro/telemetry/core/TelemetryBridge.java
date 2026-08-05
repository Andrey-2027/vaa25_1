package org.ipro.telemetry.core;

import java.util.Map;

import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.api.Telemetry;

/**
 * Статический мост к {@link Telemetry} для не-Spring кода (UI-компоненты
 * org.ip не инжектируют бины подсистемы). Устанавливается
 * TelemetryAutoConfiguration при создании бина Telemetry.
 * <p>
 * Направление: UI → telemetry; телеметрия не знает о UI.
 */
public final class TelemetryBridge {

    private static volatile Telemetry telemetry;

    private TelemetryBridge() {
    }

    public static void set(Telemetry instance) {
        telemetry = instance;
    }

    public static OperationScope beginOperation(String name) {
        Telemetry current = telemetry;
        return current != null ? current.beginOperation(name) : OperationScope.noop();
    }

    public static OperationScope beginOperation(String name, Map<String, String> context) {
        Telemetry current = telemetry;
        return current != null ? current.beginOperation(name, context) : OperationScope.noop();
    }
}