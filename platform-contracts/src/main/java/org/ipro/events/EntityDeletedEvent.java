package org.ipro.events;

import java.util.Objects;

/** Событие фактически зафиксированного удаления, доставляемое после commit. */
public record EntityDeletedEvent<T>(T entity, EventContext context)
    implements EntityEvent<T> {

    public EntityDeletedEvent {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(context, "context must not be null");
    }
}
