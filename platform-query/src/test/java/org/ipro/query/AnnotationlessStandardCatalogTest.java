package org.ipro.query;

import jakarta.persistence.Entity;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.ManagedEntityTypes;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnnotationlessStandardCatalogTest {
    @Entity static class Catalog extends StandardCatalogEntity { private String unpublished; }
    @Entity static class Document extends StandardDocumentEntity { }
    @Entity static class Internal extends BaseEntity { }
    @Entity @org.ipro.metadata.annotation.EntityMetadata static class Contributed extends BaseEntity { }

    @Test void categoryUsesTheFinalCoreFactInsteadOfRawMetadata() {
        var types = new ManagedEntityTypes(List.of(Contributed.class));
        var metadata = new MetadataResolver();
        var descriptors = new org.ipro.data.EntityDescriptorCatalog(
            new org.ipro.metadata.ManagedEntityCatalog(types),
            new org.ipro.metadata.SectionMetadataRegistry(types, metadata), metadata,
            List.of(), List.of(), List.of(type -> java.util.Optional.of(
                new org.ipro.data.EntityClassificationContributor.Contribution(
                    org.ipro.metadata.annotation.EntityKind.DOCUMENT,
                    org.ipro.metadata.FactOrigin.REGISTRATION, "verified-fixture", false))));
        var gate = mock(RlsReadGate.class);
        when(gate.canRead(Contributed.class, "reader")).thenReturn(true);
        var query = new QueryBuilderMetadataCatalog(types, metadata, gate, () -> "reader", descriptors);
        assertThat(metadata.resolve(Contributed.class).getEntityKind())
            .isEqualTo(org.ipro.metadata.annotation.EntityKind.PLAIN);
        assertThat(query.root("Contributed").category()).isEqualTo("Документ");
    }

    @Test void rootsUseEffectiveMetadataButStillRequireTheExistingReadGate() {
        var gate = mock(RlsReadGate.class);
        var user = mock(RlsCurrentUser.class);
        when(user.requireAuthenticatedUsername()).thenReturn("reader");
        when(gate.canRead(Catalog.class, "reader")).thenReturn(true);
        when(gate.canRead(Document.class, "reader")).thenReturn(true);
        var catalog = new QueryBuilderMetadataCatalog(
            new ManagedEntityTypes(List.of(Catalog.class, Document.class, Internal.class)),
            new MetadataResolver(), gate, user);
        assertThat(catalog.roots()).extracting(QueryBuilderMetadataCatalog.Entity::entityName)
            .containsExactly("Catalog", "Document");
        assertThat(catalog.root("Catalog").fields()).extracting(QueryBuilderMetadataCatalog.Field::name)
            .containsExactly("code", "name");
        when(gate.canRead(Catalog.class, "reader")).thenReturn(false);
        assertThat(catalog.roots()).extracting(QueryBuilderMetadataCatalog.Entity::entityName)
            .containsExactly("Document");
    }
}
