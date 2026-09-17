package org.ipro.data;

import org.ipro.fetch.plan.FetchScenario;

/**
 * Optional seam для telemetry policy чтения (C4.1, ADR-0007 §8).
 *
 * <p>Default — {@link #noop()}. Реализация обязана соблюдать ADR-0007 §8: успешный
 * {@code LOOKUP} не создаёт durable event на каждое нажатие, а включённая instrumentation
 * не добавляет SQL-запись к каждому lookup query.</p>
 *
 * <p>C4.8 унифицирует все публичные read-overloads: и успех, и отказ проходят через один
 * seam ({@code readAll}, {@code readPage}, {@code readDetail}, {@code readLookup},
 * {@code readSearch}, {@code readSearchWindow}, {@code readSum}). В telemetry попадают
 * только технические данные — операция, тип, сценарий, {@link ReadOutcome}, размер bounded
 * выдачи и длительность. Поисковый терм, отображаемое значение и RLS-предикаты не
 * передаются. Для {@code LOOKUP} успешные вызовы предполагают агрегированные
 * counters/timers или bounded sampling на стороне реализации: hot path автокомплита не
 * должен порождать durable event.</p>
 */
@FunctionalInterface
public interface ReadTelemetry {

    void completed(DataOperation operation,
                   Class<?> type,
                   FetchScenario scenario,
                   ReadOutcome outcome,
                   int resultCount,
                   long durationNanos);

    /** Итог read-операции: успешная выдача или отказ (исключение из pipeline). */
    enum ReadOutcome {
        SUCCESS,
        FAILED
    }

    static ReadTelemetry noop() {
        return (operation, type, scenario, outcome, resultCount, durationNanos) -> {
        };
    }
}
