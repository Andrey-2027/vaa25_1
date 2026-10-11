package org.ipro.contracts;

import org.ipro.events.AggregateSection;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.lifecycle.AggregateSaveContext;
import org.ipro.lifecycle.EntityChangedContext;
import org.ipro.lifecycle.EntityDeleteContext;
import org.ipro.lifecycle.EntitySaveContext;
import org.ipro.lifecycle.EntityUpdateContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleIdentityContractTest {
    @Test
    void everyContextAcceptsANonNumericKeyAndRetainsItsObjectIdentity() {
        Key key = new Key();
        KeyEntity original = new KeyEntity(key);
        KeyEntity updated = new KeyEntity(key);
        EventContext event = EventContext.builder(KeyEntity.class)
                .aggregateId(key).attachSection(String.class).operationName("save:KeyEntity").build();
        var save = new EntitySaveContext<>(updated, event);
        var update = new EntityUpdateContext<>(original, updated, event);
        var delete = new EntityDeleteContext<>(updated, event);
        var changed = new EntityChangedContext<>(updated, event);
        var aggregate = new AggregateSaveContext<>(updated,
                List.of(AggregateSection.attached(String.class, List.of("row"))), event);

        assertThat(save.entity()).isSameAs(updated);
        assertThat(update.original()).isSameAs(original);
        assertThat(update.updated()).isSameAs(updated);
        assertThat(delete.entity()).isSameAs(updated);
        assertThat(changed.entity()).isSameAs(updated);
        assertThat(aggregate.aggregate()).isSameAs(updated);
        assertThat(List.of(save.entityId(), update.entityId(), delete.entityId(), changed.entityId(),
                aggregate.aggregateId())).allSatisfy(id -> assertThat(id).isSameAs(key));
        assertThat(aggregate.section(String.class)).containsExactly("row");

        EntitySaveContext<? extends IdentifiableEntity<?>> unknown = save;
        assertThat(unknown.entity().getId()).isSameAs(key);
    }

    @Test
    void eventContextRemainsAuthoritativeForTransientAndPersistedIdentity() {
        KeyEntity entity = new KeyEntity(null);
        EventContext before = EventContext.forEntity(KeyEntity.class, null, EventSource.SYSTEM, "save:KeyEntity");
        assertThat(new EntitySaveContext<>(entity, before).entityId()).isNull();
        Key assigned = new Key();
        entity.setId(assigned);
        assertThat(new EntitySaveContext<>(entity, before).entityId()).isNull();
        EventContext after = before.withAggregateId(assigned);
        assertThat(new EntityChangedContext<>(entity, after).entityId()).isSameAs(assigned);
        assertThat(after.correlationId()).isEqualTo(before.correlationId());
    }

    private static final class Key { }

    private static final class KeyEntity implements IdentifiableEntity<Key> {
        private Key id;
        private KeyEntity(Key id) { this.id = id; }
        @Override public Key getId() { return id; }
        @Override public void setId(Key id) { this.id = id; }
    }
}
