package org.ipro.design;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExistingEntityMarkerTest {
    @Test void markerIsNotARegisteredRecipe() {
        var marker = RecipeCatalog.EXISTING_ENTITY;
        assertTrue(RecipeCatalog.find(marker).isEmpty());
        assertTrue(RecipeCatalog.descriptor(marker).isEmpty());
        assertFalse(RecipeCatalog.supportsDraft(marker));
        assertTrue(RecipeCatalog.recipes().stream().noneMatch(recipe -> recipe.ref().equals(marker)));
        assertTrue(RecipeCatalog.descriptors().stream().noneMatch(descriptor -> descriptor.ref().equals(marker)));
    }
    @Test void creationRejectsTheMarker() {
        assertThrows(IllegalArgumentException.class, () -> new DraftOperations().create(RecipeCatalog.EXISTING_ENTITY));
    }
    @Test void platformBasesFormOneChainToBaseEntity() {
        for (var base : RecipeCatalog.platformBases()) {
            var current = base;
            while (current.parent() != null) current = RecipeCatalog.platformBase(current.parent()).orElseThrow();
            assertEquals("org.ipro.crud.BaseEntity", current.typeId());
        }
    }
}
