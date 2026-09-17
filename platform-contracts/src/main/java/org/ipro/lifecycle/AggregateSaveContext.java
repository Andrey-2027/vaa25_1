package org.ipro.lifecycle;

import org.ipro.identity.IdentifiableEntity;
import org.ipro.events.AggregateSection;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;

import java.util.List;
import java.util.Objects;

/**
 * Контекст атомарного сохранения root и фактически подключённых owned sections.
 * Отсутствующая section представлена отсутствием записи, а пустая list означает
 * явно подключённую и очищаемую section.
 */
public record AggregateSaveContext<T extends IdentifiableEntity>(
        T aggregate,
        List<AggregateSection> sections,
        EventContext eventContext) {

    public AggregateSaveContext {
        Objects.requireNonNull(aggregate, "aggregate must not be null");
        sections = List.copyOf(Objects.requireNonNull(sections, "sections must not be null"));
        Objects.requireNonNull(eventContext, "eventContext must not be null");
        for (AggregateSection section : sections) {
            if (!eventContext.isSectionAttached(section.rowType())) {
                throw new IllegalArgumentException(
                    "Aggregate section " + section.rowType().getName()
                        + " is not marked as attached in event context");
            }
        }
    }

    public EventSource source() {
        return eventContext.source();
    }

    public Object aggregateId() {
        return eventContext.aggregateId();
    }

    /** Возвращает строки подключённой section; для отсутствующей section — пустой список. */
    public <R> List<R> section(Class<R> rowType) {
        Objects.requireNonNull(rowType, "rowType must not be null");
        return sections.stream()
            .filter(section -> section.rowType().equals(rowType))
            .findFirst()
            .map(section -> section.rows().stream().map(rowType::cast).toList())
            .orElseGet(List::of);
    }

    public boolean isSectionAttached(Class<?> rowType) {
        return eventContext.isSectionAttached(rowType);
    }
}
