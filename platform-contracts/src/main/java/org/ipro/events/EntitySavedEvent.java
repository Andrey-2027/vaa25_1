package org.ipro.events;

import java.util.Objects;

/**
 * Факт успешного вызова persistence-операции. Событие публикуется внутри
 * текущей транзакции; окончательный успех транзакции выражает EntityChangedEvent.
 */
public record EntitySavedEvent<T>(T entity, EventContext context)
    implements EntityEvent<T> {

    public EntitySavedEvent {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(context, "context must not be null");
    }
}
