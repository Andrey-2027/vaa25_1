package org.ipro.events;

import java.util.Objects;

/**
 * Синхронное событие перед persistence. Исключение listener'а отменяет
 * текущую операцию и должно быть преобразовано прикладным слоем в ошибку
 * валидации/сохранения.
 */
public record EntitySavingEvent<T>(T entity, EventContext context)
    implements EntityEvent<T> {

    public EntitySavingEvent {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(context, "context must not be null");
    }
}
