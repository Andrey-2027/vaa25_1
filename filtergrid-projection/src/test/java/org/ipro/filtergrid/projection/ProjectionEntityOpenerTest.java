package org.ipro.filtergrid.projection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProjectionEntityOpenerTest {
    record Entity(String id) {}

    @Test
    void resolvesScalarKeyAndPassesEntityToConsumer() {
        var opener = ProjectionEntityOpener.resolving((type, key) -> new Entity(String.valueOf(key.value())), Entity.class);
        var received = new java.util.concurrent.atomic.AtomicReference<Entity>();

        opener.asConsumer(received::set).accept(new CompositeRowKey(java.util.List.of(42L)));

        assertThat(received.get()).isEqualTo(new Entity("42"));
    }

    @Test
    void preservesCompositeKeyForResolver() {
        var key = new CompositeRowKey(java.util.List.of(1L, 2L));
        var received = new java.util.concurrent.atomic.AtomicReference<CompositeRowKey>();
        var opener = ProjectionEntityOpener.resolving((type, actual) -> {
            received.set(actual);
            return new Entity("ok");
        }, Entity.class);

        opener.open(key);

        assertThat(received.get()).isEqualTo(key);
    }
}
