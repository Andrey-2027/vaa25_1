package org.ipro.events;

import java.util.List;
import java.util.Objects;

/**
 * Данные одной фактически подключённой секции в AggregateSavingEvent.
 * Пустой {@code rows} означает подключённую очищенную секцию, а не отсутствие
 * секции; отсутствие выражается отсутствием этого объекта и класса в контексте.
 */
public record AggregateSection(Class<?> rowType, List<?> rows) {

    public AggregateSection {
        rowType = Objects.requireNonNull(rowType, "rowType must not be null");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows must not be null"));
    }

    public static <R> AggregateSection attached(Class<R> rowType, List<R> rows) {
        return new AggregateSection(rowType, rows);
    }
}
