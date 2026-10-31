package org.ipro.design.runtime;

import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityExposure;
import org.ipro.design.RuntimeDesignSnapshot.CatalogOwnerFacts;
import org.ipro.design.RuntimeDesignSnapshot.TypeFacts;
import org.ipro.metadata.CatalogOwnerDescriptor;
import org.ipro.metadata.FactOrigin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** E3.R: owner facts пересекают границу модулей только как portable record. */
class CatalogOwnerFactsBridgeTest {

    private static final class FixtureType {
    }

    @Test
    void platformOwnerDescriptorBecomesPortableFacts() {
        EntityDescriptor descriptor = descriptor(
            new CatalogOwnerDescriptor("counterparty", "com.example.Counterparty"));

        assertEquals(new CatalogOwnerFacts("counterparty", "com.example.Counterparty"),
            EntityDesignRuntimeProjection.ownerFacts(descriptor));
    }

    @Test
    void ordinaryCatalogYieldsNullFacts() {
        assertNull(EntityDesignRuntimeProjection.ownerFacts(descriptor(null)));
    }

    @Test
    void typeFactsKeepOwnerOptionalAndBackwardCompatible() {
        TypeFacts legacy = new TypeFacts("t", "CATALOG", "DERIVED", "src", "STANDARD_ROOT",
            "PLATFORM_DEFAULT", "", "DERIVED", "", Set.of(), Set.of(), false, false, "",
            List.of(), List.of(), List.of(), List.of(), List.of(), "", List.of());
        assertNull(legacy.owner());

        TypeFacts withOwner = new TypeFacts("t", "CATALOG", "DERIVED", "src", "STANDARD_ROOT",
            "PLATFORM_DEFAULT", "", "DERIVED", "", Set.of(), Set.of(), false, false, "",
            List.of(), List.of(), List.of(), List.of(), List.of(), "", List.of(),
            new CatalogOwnerFacts("counterparty", "com.example.Counterparty"));
        assertEquals(new CatalogOwnerFacts("counterparty", "com.example.Counterparty"),
            withOwner.owner());
    }

    private static EntityDescriptor descriptor(CatalogOwnerDescriptor owner) {
        return new EntityDescriptor(FixtureType.class, EntityExposure.STANDARD_ROOT, true, true,
            new EntityCapabilities(Set.of(), Set.of(), "test fixture"), "test fixture",
            FactOrigin.DERIVED, FixtureType.class.getName(),
            FactOrigin.PLATFORM_DEFAULT, "", owner);
    }
}
