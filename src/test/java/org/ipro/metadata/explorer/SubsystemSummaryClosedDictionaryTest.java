package org.ipro.metadata.explorer;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Замкнутость словаря «Структуры подсистем» (П1, срез 2): {@link SubsystemSummaryAssembler}'s
 * records ({@link SubsystemSummaryAssembler.Catalog}, {@link SubsystemSummaryAssembler.SubsystemRef},
 * {@link SubsystemSummaryAssembler.EntityFacet}, {@link SubsystemSummaryAssembler.Group}) не
 * содержат Object payload, Vaadin-типов и callback'ов — общий {@link ClosedDictionaryVerifier}.
 */
class SubsystemSummaryClosedDictionaryTest {

    @Test
    void subsystemCatalogRecordsAreClosedDictionary() {
        ClosedDictionaryVerifier.verify(List.of(
            SubsystemSummaryAssembler.Catalog.class,
            SubsystemSummaryAssembler.SubsystemRef.class,
            SubsystemSummaryAssembler.EntityFacet.class,
            SubsystemSummaryAssembler.Group.class));
    }
}
