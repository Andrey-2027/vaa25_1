package org.ipro.lifecycle;

import org.ipro.identity.IdentifiableEntity;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;

import java.util.Objects;

/** Контекст after-commit реакции на изменение entity. */
public record EntityChangedContext<T extends IdentifiableEntity>(
        T entity,
        EventContext eventContext) {

    public EntityChangedContext {
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
