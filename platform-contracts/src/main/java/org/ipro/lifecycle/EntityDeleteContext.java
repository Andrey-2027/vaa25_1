package org.ipro.lifecycle;

import org.ipro.identity.IdentifiableEntity;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;

import java.util.Objects;

/** Контекст lifecycle-операции удаления entity. */
public record EntityDeleteContext<T extends IdentifiableEntity>(
        T entity,
        EventContext eventContext) {

    public EntityDeleteContext {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(eventContext, "eventContext must not be null");
    }

    public EventSource source() {
        return eventContext.source();
    }

    public Object entityId() {
        return eventContext.aggregateId();
    }
}
