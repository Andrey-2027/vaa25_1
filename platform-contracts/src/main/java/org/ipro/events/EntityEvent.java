package org.ipro.events;

/**
 * Общий типизированный контракт событий, относящихся к одной сущности.
 *
 * @param <T> тип сущности или корневого агрегата
 */
public interface EntityEvent<T> {

    T entity();

    EventContext context();
}
