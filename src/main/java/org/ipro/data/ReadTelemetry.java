package org.ipro.data;

import org.ipro.fetch.plan.FetchScenario;

/**
 * Optional seam для telemetry policy чтения (C4.1, ADR-0007 §8).
 *
 * <p>Default — {@link #noop()}. Реализация обязана соблюдать ADR-0007 §8: успешный
 * {@code LOOKUP} не создаёт durable event на каждое нажатие, а включённая instrumentation
 * не добавляет SQL-запись к каждому lookup query.</p>
 */
@FunctionalInterface
public interface ReadTelemetry {

    void completed(DataOperation operation,
                   Class<?> type,
                   FetchScenario scenario,
                   int resultCount,
                   long durationNanos);

    static ReadTelemetry noop() {
        return (operation, type, scenario, resultCount, durationNanos) -> {
        };
    }
}
