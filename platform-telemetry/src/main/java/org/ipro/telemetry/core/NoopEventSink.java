package org.ipro.telemetry.core;

import org.ipro.telemetry.api.AggregateStats;
import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.TelemetryEvent;

/**
 * Sink-заглушка для ipro.telemetry.db-journal=false: события никуда не
 * пишутся. Обработчики (SlowOperationHandler) при isNoop() возвращаются
 * к файловому логу с деревом фреймов.
 */
public final class NoopEventSink implements EventSink {

    public static final NoopEventSink INSTANCE = new NoopEventSink();

    private NoopEventSink() {
    }

    @Override
    public void accept(TelemetryEvent event) {
    }

    @Override
    public void acceptDurable(TelemetryEvent event) {
    }

    @Override
    public void acceptStats(AggregateStats stats) {
    }

    @Override
    public void flush() {
    }

    @Override
    public boolean isNoop() {
        return true;
    }
}
