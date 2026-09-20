package org.ipro.telemetry.core;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Ячейка sink'а — {@link AtomicReference}, а не volatile-поле (D3.5.8-fix).
     * Причина не видимость, а <b>атомарность пары check/write</b>: {@code clearSink} обязан
     * стирать только свой sink, и «проверил, потом стёр» двумя операциями над volatile-полем
     * гонку не закрывает — между проверкой и присваиванием другой контекст успевает установить
     * свой sink, который затем стирается. Ровно сценарий hot reload / соседних контекстов,
     * от которого compare-and-clear и существует.
     */
    private static final AtomicReference<EventSink> sink = new AtomicReference<>();

    private TelemetryBridge() {
    }

    public static void set(Telemetry instance) {
        telemetry = instance;
    }

    public static void setSink(EventSink instance) {
        sink.set(instance);
    }

    /**
     * Снимает sink, только если в ячейке всё ещё он: compare-and-clear (D3.5.6).
     *
     * <p>Адресность здесь не перестраховка. Мост статичен, а тесты, перезапуск контекста и
     * hot reload живут в одной JVM: безусловная очистка при закрытии контекста обезоружила бы
     * ещё живой соседний. Такой класс ошибок в проекте уже ловили — первая версия
     * {@code DeclaredNameBridge} стирала единственную ячейку на любом {@code destroy()} и уронила
     * тест, который проходил в одиночку (см. javadoc {@code TelemetrySeamBridgesTest}).</p>
     *
     * <p>Стирание выполняется одной атомарной операцией {@link AtomicReference#compareAndSet}:
     * если между чтением и записью соседний контекст поставил свой sink, CAS провалится и
     * чужой sink останется на месте (D3.5.8-fix).</p>
     */
    public static void clearSink(EventSink expected) {
        if (expected != null) {
            sink.compareAndSet(expected, null);
        }
    }

    public static EventSink getSink() {
        return sink.get();
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