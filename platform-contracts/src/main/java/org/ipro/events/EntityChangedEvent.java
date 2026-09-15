package org.ipro.events;

import java.util.Objects;

/**
 * Событие фактически зафиксированного изменения. Публикуется после commit,
 * поэтому его можно использовать для поиска, уведомлений и интеграций.
 */
public record EntityChangedEvent<T>(T entity, EventContext context)
    implements EntityEvent<T> {

    public EntityChangedEvent {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(context, "context must not be null");
    }
}
