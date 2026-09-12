package org.ipro.events;

import java.util.List;
import java.util.Objects;

/**
 * Синхронное событие перед сохранением агрегата «шапка + секции».
 *
 * <p>Секции передаются как read-only снимки, а их presence дополнительно
 * подтверждается {@link EventContext#attachedSections()}. Поэтому пустые строки
 * у переданной секции не смешиваются с отсутствующей секцией.</p>
 */
public record AggregateSavingEvent<T>(
    T aggregate,
    List<AggregateSection> sections,
    EventContext context
) {

    public AggregateSavingEvent {
        Objects.requireNonNull(aggregate, "aggregate must not be null");
        sections = List.copyOf(Objects.requireNonNull(sections, "sections must not be null"));
        Objects.requireNonNull(context, "context must not be null");
        for (AggregateSection section : sections) {
            if (!context.isSectionAttached(section.rowType())) {
                throw new IllegalArgumentException(
                    "Aggregate section " + section.rowType().getName()
                        + " is not marked as attached in event context");
            }
        }
    }

    /** Совместимость с общим контрактом entity-событий для aggregate handlers. */
    public T entity() {
        return aggregate;
    }
}
