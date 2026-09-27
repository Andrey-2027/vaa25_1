package org.ip.model;

import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NomenclatureKindTest {

    @Test
    void nomenclatureIsResolvedAsCatalog() {
        assertThat(new MetadataResolver().resolve(Nomenclature.class).getEntityKind())
            .isEqualTo(EntityKind.CATALOG);
    }
}
