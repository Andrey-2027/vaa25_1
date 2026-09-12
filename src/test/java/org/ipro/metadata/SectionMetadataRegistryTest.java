package org.ipro.metadata;

import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.PrdSpecOper;
import org.ip.model.Nomenclature;
import org.ip.model.NomAttributeValue;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ipro.metadata.annotation.SectionPersistenceMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SectionMetadataRegistryTest {

    @Test
    void buildsDeterministicCatalogOfApplicationOwnedSections() {
        SectionMetadataRegistry registry = new SectionMetadataRegistry("org.ip", new MetadataResolver());
        registry.afterPropertiesSet();

        assertThat(registry.all()).extracting(TableSectionMetadataInfo::getKey)
            .containsExactly(
                "Nomenclature.NomAttributeValue",
                "PrdSpec.PrdSpecMtr",
                "PrdSpec.PrdSpecOper",
                "ReceivingDocument.ReceivingDocumentItem");
        assertThat(registry.forOwner(Nomenclature.class))
            .extracting(TableSectionMetadataInfo::getRowClass)
            .containsExactly(NomAttributeValue.class);
        assertThat(registry.forOwner(PrdSpec.class))
            .extracting(TableSectionMetadataInfo::getRowClass)
            .containsExactlyInAnyOrder(PrdSpecMtr.class, PrdSpecOper.class);

        TableSectionMetadataInfo receiving = registry.findByRow(ReceivingDocumentItem.class)
            .orElseThrow();
        assertThat(receiving.getOwnerClass()).isEqualTo(ReceivingDocument.class);
        assertThat(receiving.getPersistenceMode())
            .isEqualTo(SectionPersistenceMode.MUTABLE_REPLACE_ALL);
    }
}
