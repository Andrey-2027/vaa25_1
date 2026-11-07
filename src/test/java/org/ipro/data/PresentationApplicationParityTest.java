package org.ipro.data;

import org.ip.model.AccountingType;
import org.ip.model.NomSklAttribute;
import org.ip.model.PrdSpec;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.Workshop;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PresentationApplicationParityTest {
    @Test void realCatalogsKeepDifferentNamesWithTheSameSearchAndRlsDefaults() {
        MetadataResolver metadata = new MetadataResolver();
        PresentationResolver resolver = new PresentationResolver(metadata,
            new InstanceNameResolver(List.of(AccountingType.class, Workshop.class), metadata));
        AccountingType type = new AccountingType("01", "Учёт");
        Workshop workshop = new Workshop("01", "Цех");
        assertThat(resolver.displayName(type)).isEqualTo("Учёт");
        assertThat(resolver.displayName(workshop)).isEqualTo("01");
        assertThat(resolver.searchFields(AccountingType.class)).containsExactly("code", "name");
        assertThat(resolver.searchFields(Workshop.class)).containsExactly("code", "name");
        assertThat(resolver.sortFields(AccountingType.class)).isEmpty();
        assertThat(resolver.sortFields(Workshop.class)).containsExactly("code");
        assertThat(resolver.rlsLabels(AccountingType.class, type).name()).isEqualTo("Учёт");
        assertThat(resolver.rlsLabels(Workshop.class, workshop).name()).isEqualTo("Цех");
    }

    @Test void actualSearchAndRlsRolesRemainIndependentOfSorting() {
        PresentationResolver resolver = new PresentationResolver(new MetadataResolver());
        assertThat(resolver.rlsLabelPaths(NomSklAttribute.class)).containsExactly("nomenclature", "attrType");
        assertThat(resolver.sortFields(NomSklAttribute.class)).containsExactly("attrType", "nomenclature");
        assertThat(resolver.searchFields(PrdSpec.class)).containsExactly("codeSpec", "draft");
        assertThat(resolver.rlsLabelPaths(PrdSpec.class)).containsExactly("codeSpec", "nomenclature.name");
        assertThat(resolver.searchFields(UnitOfMeasurement.class)).containsExactly("shortCode");
        assertThat(resolver.rlsLabelPaths(UnitOfMeasurement.class)).containsExactly("code", "name");
    }
}
