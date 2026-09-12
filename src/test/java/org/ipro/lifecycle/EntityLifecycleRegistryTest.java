package org.ipro.lifecycle;

import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ipro.events.AggregateSection;
import org.ipro.events.EntityChangedEvent;
import org.ipro.events.EventContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityLifecycleRegistryTest {

    @Test
    void indexesOneHandlerByEntityTypeAndDoesNotInvokeItForAnotherType() {
        RecordingLifecycle handler = new RecordingLifecycle();
        EntityLifecycleRegistry registry = new EntityLifecycleRegistry(List.of(handler));
        Nomenclature nomenclature = new Nomenclature();
        EventContext context = EventContext.forEntity(
            Nomenclature.class, null, org.ipro.events.EventSource.SYSTEM, "save:Nomenclature");

        registry.beforeSave(Nomenclature.class, nomenclature, context);
        registry.onSave(Nomenclature.class, nomenclature, context);

        assertThat(registry.find(Nomenclature.class)).contains(handler);
        assertThat(handler.beforeSaveCount).isEqualTo(1);
        assertThat(handler.onSaveCount).isEqualTo(1);
        assertThat(registry.find(PrdSpec.class)).isEmpty();
    }

    @Test
    void rejectsDuplicateAuthoritativeHandlersAtConstruction() {
        assertThatThrownBy(() -> new EntityLifecycleRegistry(List.of(
                new RecordingLifecycle(), new RecordingLifecycle())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(Nomenclature.class.getName())
            .hasMessageContaining("More than one EntityLifecycle");
    }

    @Test
    void dispatchesBeforeUpdateAndExposesFieldDifference() {
        RecordingLifecycle handler = new RecordingLifecycle();
        EntityLifecycleRegistry registry = new EntityLifecycleRegistry(List.of(handler));
        Nomenclature original = new Nomenclature();
        original.setId(42L);
        original.setCode("OLD");
        Nomenclature updated = new Nomenclature();
        updated.setId(42L);
        updated.setCode("NEW");
        EventContext context = EventContext.forEntity(
            Nomenclature.class, 42L, org.ipro.events.EventSource.SYSTEM,
            "update:Nomenclature");

        registry.beforeUpdate(Nomenclature.class, original, updated, context);

        assertThat(handler.beforeUpdateCount).isEqualTo(1);
        assertThat(handler.codeChanged).isTrue();
    }

    @Test
    void aggregateContextKeepsAttachedEmptySectionDistinctFromAbsentSection() {
        EventContext context = EventContext.builder(PrdSpec.class)
            .attachSection(PrdSpecMtr.class)
            .operationName("save:PrdSpec")
            .build();
        AggregateSaveContext<PrdSpec> aggregateContext = new AggregateSaveContext<>(
            new PrdSpec(),
            List.of(AggregateSection.attached(PrdSpecMtr.class, List.of())),
            context);

        assertThat(aggregateContext.isSectionAttached(PrdSpecMtr.class)).isTrue();
        assertThat(aggregateContext.section(PrdSpecMtr.class)).isEmpty();
        assertThat(aggregateContext.section(Nomenclature.class)).isEmpty();
    }

    @Test
    void deliversAfterCommitCallbackFromChangedEvent() {
        RecordingLifecycle handler = new RecordingLifecycle();
        EntityLifecycleRegistry registry = new EntityLifecycleRegistry(List.of(handler));
        Nomenclature nomenclature = new Nomenclature();
        EventContext context = EventContext.forEntity(
            Nomenclature.class, 42L, org.ipro.events.EventSource.SYSTEM, "save:Nomenclature");

        registry.onEntityChanged(new EntityChangedEvent<>(nomenclature, context));

        assertThat(handler.afterCommitCount).isEqualTo(1);
    }

    private static final class RecordingLifecycle implements EntityLifecycle<Nomenclature> {
        private int beforeSaveCount;
        private int onSaveCount;
        private int beforeUpdateCount;
        private int afterCommitCount;
        private boolean codeChanged;

        @Override
        public Class<Nomenclature> entityType() {
            return Nomenclature.class;
        }

        @Override
        public void beforeSave(EntitySaveContext<Nomenclature> context) {
            beforeSaveCount++;
        }

        @Override
        public void onSave(EntitySaveContext<Nomenclature> context) {
            onSaveCount++;
        }

        @Override
        public void beforeUpdate(EntityUpdateContext<Nomenclature> context) {
            beforeUpdateCount++;
            codeChanged = context.changed(Nomenclature::getCode);
        }

        @Override
        public void afterCommit(EntityChangedContext<Nomenclature> context) {
            afterCommitCount++;
        }
    }
}
