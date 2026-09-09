package org.ipro.search;

import org.ip.config.GlobalSearchApplicationConfig;
import org.ip.model.Nomenclature;
import org.ip.model.ReceivingDocument;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class JpaGlobalSearchProviderTest {

    private GlobalSearchCatalog catalog;
    private JpaGlobalSearchProvider<Nomenclature> nomenclatureProvider;
    private JpaGlobalSearchProvider<ReceivingDocument> documentProvider;

    @BeforeEach
    void setUp() {
        catalog = new GlobalSearchCatalog(
            new GlobalSearchApplicationConfig().globalSearchConfig(), new MetadataResolver());
        nomenclatureProvider = new JpaGlobalSearchProvider<>(Nomenclature.class);
        documentProvider = new JpaGlobalSearchProvider<>(ReceivingDocument.class);
    }

    @Test
    void displayNameAndIdAreMappedToSafeResultParts() {
        Nomenclature value = new Nomenclature();
        value.setId(7L);
        value.setCode("N-007");
        value.setName("Гайка М8");
        GlobalSearchSource source = catalog.requireSource(Nomenclature.class);

        assertThat(nomenclatureProvider.idOf(value)).isEqualTo(7L);
        assertThat(nomenclatureProvider.displayValue(value, source))
            .isEqualTo("N-007 Гайка М8");
    }

    @Test
    void matchClassificationUsesExactThenPrefixThenSubstring() {
        Nomenclature value = new Nomenclature();
        value.setCode("N-007");
        value.setName("Гайка М8");
        GlobalSearchSource source = catalog.requireSource(Nomenclature.class);

        assertThat(nomenclatureProvider.classify(value, source, "n-007"))
            .isEqualTo(new GlobalSearchMatch(GlobalSearchMatchKind.EXACT, "code"));
        assertThat(nomenclatureProvider.classify(value, source, "n-"))
            .isEqualTo(new GlobalSearchMatch(GlobalSearchMatchKind.PREFIX, "code"));
        assertThat(nomenclatureProvider.classify(value, source, "м8"))
            .isEqualTo(new GlobalSearchMatch(GlobalSearchMatchKind.SUBSTRING, "name"));
    }

    @Test
    void explicitDisplayFieldsAvoidLazyDisplayNameDependencies() {
        org.ip.model.PrdSpec value = new org.ip.model.PrdSpec();
        value.setCodeSpec("SP-1");
        value.setDraft("черновик");
        GlobalSearchSource source = catalog.requireSource(org.ip.model.PrdSpec.class);
        JpaGlobalSearchProvider<org.ip.model.PrdSpec> provider =
            new JpaGlobalSearchProvider<>(org.ip.model.PrdSpec.class);

        assertThat(provider.displayValue(value, source))
            .isEqualTo("SP-1 — черновик");
    }

    @Test
    void explicitDisplayFieldsAreUsedWhenEntityHasNoDisplayContractForSource() {
        ReceivingDocument value = new ReceivingDocument();
        value.setNumber("RD-1");
        value.setDate(LocalDate.of(2026, 2, 3));
        GlobalSearchSource source = catalog.requireSource(ReceivingDocument.class);

        assertThat(documentProvider.displayValue(value, source))
            .isEqualTo("RD-1 — 2026-02-03");
    }
}
