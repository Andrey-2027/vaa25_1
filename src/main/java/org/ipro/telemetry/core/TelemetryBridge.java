package org.ipro.telemetry.core;

import java.util.Map;

import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.api.Telemetry;

/**
 * Статический мост к {@link Telemetry} и {@link EventSink} для не-Spring кода
 * (UI-компоненты org.ip не инжектируют бины подсистемы, а сериализуемые
 * Vaadin-объекты не могут хранить ссылку на бин). Устанавливается
 * TelemetryAutoConfiguration при создании бинов Telemetry/EventSink.
 * <p>
 * Направление: UI → telemetry; телеметрия не знает о UI.
 */
public final class TelemetryBridge {

    private static volatile Telemetry telemetry;
    private static volatile EventSink sink;

    private TelemetryBridge() {
    }

    public static void set(Telemetry instance) {
        telemetry = instance;
    }

    public static void setSink(EventSink instance) {
        sink = instance;
    }

    public static EventSink getSink() {
        return sink;
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