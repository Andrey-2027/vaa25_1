package org.ipro.data;

import org.ipro.fetch.instance.InstanceName;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.RlsLabels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PresentationResolverTest {
    @EntityMetadata(selectColumns = "decoy")
    public static class AccountingType implements HasDisplayName {
        @FieldMetadata String code = "C";
        @FieldMetadata String name = "Name";
        @FieldMetadata String decoy = "Ignored column";
        public Long getId() { return 1L; }
        public String getDisplayName() { return name; }
    }

    @EntityMetadata(selectColumns = "decoy", displaySortFields = "code")
    public static class Workshop extends AccountingType {
        @Override public String getDisplayName() { return code; }
    }

    @EntityMetadata(selectColumns = "decoy", displaySortFields = "code")
    @InstanceName("name")
    @SearchFields("code")
    @RlsLabels(code = "name", name = "decoy")
    public static class Independent extends AccountingType { }

    @EntityMetadata
    @InstanceName("name")
    public static class Named extends AccountingType { }

    @EntityMetadata
    @RlsLabels(code = "missing")
    public static class Invalid extends AccountingType { }

    @EntityMetadata
    public static class NoNames {
        public Long getId() { return 7L; }
    }

    @EntityMetadata(selectColumns = "reference")
    @InstanceName("name")
    public static class NamedWithUnrelatedSelectionColumn extends AccountingType {
        @FieldMetadata(type = org.ipro.metadata.annotation.FieldType.ENTITY_REFERENCE)
        AccountingType reference;
    }

    @jakarta.persistence.Entity
    public static class StandardCatalog extends org.ipro.crud.StandardCatalogEntity { }

    @jakarta.persistence.Entity
    public static class StandardDocument extends org.ipro.crud.StandardDocumentEntity { }

    private PresentationResolver resolver(Class<?>... types) {
        MetadataResolver metadata = new MetadataResolver();
        return new PresentationResolver(metadata, new InstanceNameResolver(List.of(types), metadata));
    }

    @Test void sameCodeAndNameDoNotForceTheSameDisplayName() {
        PresentationResolver resolver = resolver(AccountingType.class, Workshop.class);
        assertThat(resolver.displayName(new AccountingType())).isEqualTo("Name");
        assertThat(resolver.displayName(new Workshop())).isEqualTo("C");
        for (Class<?> type : List.of(AccountingType.class, Workshop.class)) {
            assertThat(resolver.searchFields(type)).containsExactly("code", "name");
            assertThat(resolver.rlsLabels(type, type == Workshop.class ? new Workshop() : new AccountingType()))
                .isEqualTo(new org.ipro.rls.RlsDimensionValueLabelResolver.Labels("C", "Name"));
        }
        assertThat(resolver.sortFields(AccountingType.class)).isEmpty();
        assertThat(resolver.sortFields(Workshop.class)).containsExactly("code");
    }

    @Test void allFourRolesAreIndependentOfColumnsAndEachOther() {
        PresentationResolver resolver = resolver(Independent.class);
        Independent value = new Independent();
        assertThat(resolver.displayName(value)).isEqualTo("Name");
        assertThat(resolver.searchFields(Independent.class)).containsExactly("code");
        assertThat(resolver.sortFields(Independent.class)).containsExactly("code");
        assertThat(resolver.rlsLabels(Independent.class, value))
            .isEqualTo(new org.ipro.rls.RlsDimensionValueLabelResolver.Labels("Name", "Ignored column"));
        assertThat(resolver.displayPaths(Independent.class)).containsExactly("name");
    }

    @Test void declaredInstanceNameIsUsedForSearchEvenWithoutInjectedResolver() {
        assertThat(new PresentationResolver(new MetadataResolver()).searchFields(Named.class))
            .containsExactly("name");
    }

    @Test void absentDeclarationsHaveExplicitEmptyAndSafeDefaults() {
        PresentationResolver resolver = resolver(NoNames.class);
        assertThat(resolver.searchFields(NoNames.class)).isEmpty();
        assertThat(resolver.sortFields(NoNames.class)).isEmpty();
        assertThat(resolver.displayName(new NoNames())).isEqualTo("NoNames#7");
        assertThat(resolver.rlsLabels(NoNames.class, new NoNames()))
            .isEqualTo(new org.ipro.rls.RlsDimensionValueLabelResolver.Labels(null, "NoNames#7"));
    }

    @Test void invalidRlsPathIsAnAddressedConfigurationError() {
        assertThatThrownBy(() -> resolver(Invalid.class).validate(Invalid.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("@RlsLabels").hasMessageContaining(Invalid.class.getName())
            .hasMessageContaining("missing");
    }

    @Test void selectionColumnsDoNotExpandTheBaseLookupPlan() {
        PresentationResolver resolver = resolver(NamedWithUnrelatedSelectionColumn.class);
        assertThat(resolver.displayPaths(NamedWithUnrelatedSelectionColumn.class)).containsExactly("name");
        assertThat(resolver.lookupFetchPaths(NamedWithUnrelatedSelectionColumn.class)).isEmpty();
    }

    @Test void standardBaseSortDefaultsRemainAvailableWithoutAnEntityMetadataAnnotation() {
        PresentationResolver resolver = resolver(StandardCatalog.class, StandardDocument.class);
        assertThat(resolver.sortFields(StandardCatalog.class)).containsExactly("code", "name");
        assertThat(resolver.sortFields(StandardDocument.class)).containsExactly("number", "date");
        StandardDocument document = new StandardDocument();
        document.setNumber("DOC");
        document.setDate(java.time.LocalDate.of(2026, 11, 5));
        assertThat(resolver.rlsLabels(StandardDocument.class, document))
            .isEqualTo(new org.ipro.rls.RlsDimensionValueLabelResolver.Labels("DOC", "2026-11-05"));
    }
}
