package org.ipro.lifecycle;

import org.ip.model.Nomenclature;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntityUpdateContextTest {

    @Test
    void changedOrUnknownFollowsPreciseDiffWithDistinctOriginal() {
        Nomenclature original = nomenclature(42L, "OLD");
        Nomenclature changed = nomenclature(42L, "NEW");
        Nomenclature same = nomenclature(42L, "OLD");

        assertThat(context(original, changed).changedOrUnknown(Nomenclature::getCode)).isTrue();
        assertThat(context(original, same).changedOrUnknown(Nomenclature::getCode)).isFalse();
    }

    @Test
    void changedOrUnknownTreatsManagedInstanceAsChanged() {
        Nomenclature managed = nomenclature(42L, "CODE");

        EntityUpdateContext<Nomenclature> context = context(managed, managed);

        assertThat(context.hasDistinctOriginal()).isFalse();
        assertThat(context.changed(Nomenclature::getCode)).isFalse();
        assertThat(context.changedOrUnknown(Nomenclature::getCode)).isTrue();
    }

    private static Nomenclature nomenclature(Long id, String code) {
        Nomenclature nomenclature = new Nomenclature();
        nomenclature.setId(id);
        nomenclature.setCode(code);
        return nomenclature;
    }

    private static EntityUpdateContext<Nomenclature> context(
            Nomenclature original, Nomenclature updated) {
        return new EntityUpdateContext<>(original, updated, EventContext.forEntity(
            Nomenclature.class, 42L, EventSource.SYSTEM, "update:Nomenclature"));
    }
}
