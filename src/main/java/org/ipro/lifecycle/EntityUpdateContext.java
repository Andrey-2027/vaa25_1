package org.ipro.lifecycle;

import org.ipro.crud.IdentifiableEntity;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;

import java.util.Objects;
import java.util.function.Function;

/** Контекст изменения существующей entity с исходным состоянием до persistence. */
public record EntityUpdateContext<T extends IdentifiableEntity>(
        T original,
        T updated,
        EventContext eventContext) {

    public EntityUpdateContext {
        Objects.requireNonNull(original, "original must not be null");
        Objects.requireNonNull(updated, "updated must not be null");
        Objects.requireNonNull(eventContext, "eventContext must not be null");
    }

    public EventSource source() {
        return eventContext.source();
    }

    public Object entityId() {
        return eventContext.aggregateId();
    }

    public String operationName() {
        return eventContext.operationName();
    }

    /**
     * Отличает загруженный detached-снимок от уже managed instance, переданного
     * вызывающим кодом. Для managed instance callback всё равно доставляется, но
     * надёжное сравнение старых значений недоступно.
     */
    public boolean hasDistinctOriginal() {
        return original != updated;
    }

    /** Сравнить значение поля в исходном и новом состоянии. */
    public boolean changed(Function<? super T, ?> field) {
        Objects.requireNonNull(field, "field must not be null");
        return !Objects.equals(field.apply(original), field.apply(updated));
    }
}
