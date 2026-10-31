package org.ipro.metadata;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class EffectiveStandardMetadataTest {
    @Entity static class Catalog extends StandardCatalogEntity {
        private String unpublished;
        @FieldMetadata private String extra;
    }
    @Entity @TableSections(Row.class) static class Document extends StandardDocumentEntity { }
    @Entity @TableSectionMetadata(parentEntity = Document.class, parentField = "owner")
    static class Row extends BaseEntity { @ManyToOne private Document owner; }
    @Entity static class Internal extends BaseEntity { }
    @Entity @EntityMetadata(listFormTitle = "Local", icon = "BOOK", order = 7,
        displaySortFields = {"name"}, listColumns = {"name"})
    static class Local extends StandardCatalogEntity { }
    @Entity @EntityMetadata(kind = EntityKind.CATALOG)
    static class Conflict extends StandardDocumentEntity { }
    @Entity @EntityMetadata(kind = EntityKind.DOCUMENT)
    static class Custom extends BaseEntity { @FieldMetadata private java.time.LocalDateTime created; }

    @Test void bareEntitiesHaveEffectiveDefaultsWithoutSyntheticAnnotations() {
        var resolver = new MetadataResolver();
        var catalog = resolver.find(Catalog.class).orElseThrow();
        assertThat(catalog.getAnnotation()).isNull();
        assertThat(catalog.getEntityKind()).isEqualTo(EntityKind.CATALOG);
        assertThat(catalog.getEntityKindOrigin()).isEqualTo(FactOrigin.DERIVED);
        assertThat(catalog.getListFormTitle()).isEqualTo("Catalog");
        assertThat(catalog.getItemFormTitle()).isEqualTo("Catalog");
        assertThat(catalog.getSelectionFormTitle()).isEqualTo("Catalog");
        assertThat(catalog.getSubsystem()).isEqualTo(Subsystem.NoSubsystem.class);
        assertThat(catalog.getIcon()).isEqualTo("FILE");
        assertThat(catalog.getOrder()).isEqualTo(999);
        assertThat(catalog.getDisplaySortFields()).containsExactly("code", "name");
        assertThat(catalog.getPropertyOrigin("displaySortFields")).isEqualTo(FactOrigin.DERIVED);
        assertThat(catalog.getPropertySymbol("displaySortFields")).isEqualTo(StandardCatalogEntity.class.getName());
        assertThat(catalog.getFormFields()).extracting(FieldMetadataInfo::getName)
            .containsExactly("code", "name", "extra");
        assertThat(resolver.resolve(Document.class).getDisplaySortFields()).containsExactly("number", "date");
        assertThat(resolver.find(Internal.class)).isEmpty();
        assertThat(resolver.find(StandardCatalogEntity.class)).isEmpty();
    }

    @Test void overridesAndCustomDocumentsKeepTheirOwnModelContract() {
        var resolver = new MetadataResolver();
        var local = resolver.resolve(Local.class);
        assertThat(local.getAnnotation()).isNotNull();
        assertThat(local.getListFormTitle()).isEqualTo("Local");
        assertThat(local.getIcon()).isEqualTo("BOOK");
        assertThat(local.getOrder()).isEqualTo(7);
        assertThat(local.getDisplaySortFields()).containsExactly("name");
        assertThat(local.getPropertyOrigin("displaySortFields")).isEqualTo(FactOrigin.EXPLICIT);
        assertThat(local.getPropertyOrigin("itemFormTitle")).isEqualTo(FactOrigin.DERIVED);
        assertThat(resolver.resolve(Custom.class).getEntityKind()).isEqualTo(EntityKind.DOCUMENT);
        assertThat(resolver.resolve(Custom.class).getDisplaySortFields()).isEmpty();
        assertThatThrownBy(() -> resolver.find(Conflict.class)).hasMessageContaining("kind conflict");
    }

    @Test void sectionsClassificationAndValidationShareTheSameApplicability() {
        var resolver = new MetadataResolver();
        var types = new ManagedEntityTypes(List.of(Catalog.class, Document.class, Row.class, Internal.class));
        var sections = new SectionMetadataRegistry(types, resolver);
        sections.rebuild();
        var descriptors = new EntityDescriptorCatalog(new ManagedEntityCatalog(types), sections, resolver, List.of());
        assertThat(descriptors.descriptorOf(Catalog.class).exposure()).isEqualTo(EntityExposure.STANDARD_ROOT);
        assertThat(descriptors.descriptorOf(Document.class).exposure()).isEqualTo(EntityExposure.STANDARD_ROOT);
        assertThat(descriptors.descriptorOf(Row.class).exposure()).isEqualTo(EntityExposure.OWNED_ROW);
        assertThat(descriptors.descriptorOf(Internal.class).exposure()).isEqualTo(EntityExposure.INTERNAL_STORE);
        assertThat(MetadataConsistencyValidator.validate(resolver, types, List.of()))
            .noneMatch(d -> d.severity() == MetadataDiagnostic.Severity.ERROR);
    }

    @Test void numberingSearchAndPresentationNeedNoLocalInfrastructureDeclarations() {
        var resolver = new MetadataResolver();
        var types = new ManagedEntityTypes(List.of(Catalog.class, Document.class, Internal.class));
        var numbering = new org.ipro.numbering.NumberingMetadataRegistry(types, MetadataResolver::isApplicable);
        numbering.rebuild();
        assertThat(numbering.forEntity(Catalog.class)).extracting(
            org.ipro.numbering.NumberingMetadataRegistry.NumberedFieldInfo::fieldName).containsExactly("code");
        assertThat(numbering.forEntity(Document.class)).extracting(
            org.ipro.numbering.NumberingMetadataRegistry.NumberedFieldInfo::fieldName).containsExactly("number");
        assertThat(new org.ipro.data.SearchFieldResolver(resolver).resolve(Catalog.class, List.of()))
            .contains("code", "name").doesNotContain("unpublished");
        assertThat(new org.ipro.data.SearchFieldResolver(resolver).resolve(Document.class, List.of()))
            .containsExactly("number");
        var catalog = new Catalog(); catalog.setCode("001"); catalog.setName("Item");
        assertThat(new org.ipro.fetch.instance.InstanceNameResolver(types.all(), resolver).resolve(catalog))
            .isEqualTo("001 — Item");
    }
}
