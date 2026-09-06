package org.ipro.filtergrid.projection;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Resolves an application entity from a projection row key and opens it. */
@FunctionalInterface
public interface ProjectionEntityOpener<E> {
    E open(CompositeRowKey key);

    static <E> ProjectionEntityOpener<E> resolving(
            BiFunction<Class<E>, CompositeRowKey, E> resolver, Class<E> entityType) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(entityType, "entityType");
        return key -> resolver.apply(entityType, key);
    }

    default Consumer<CompositeRowKey> asConsumer(Consumer<? super E> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        return key -> consumer.accept(open(key));
    }
}
