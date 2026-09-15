package org.ipro.events;

import java.util.Objects;

/** Синхронное событие перед удалением; исключение listener'а отменяет удаление. */
public record EntityDeletingEvent<T>(T entity, EventContext context)
    implements EntityEvent<T> {

    public EntityDeletingEvent {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(context, "context must not be null");
    }
}
