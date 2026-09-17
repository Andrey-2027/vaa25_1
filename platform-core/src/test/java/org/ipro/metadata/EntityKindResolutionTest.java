package org.ipro.metadata;

import org.ipro.crud.BaseEntity;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.annotation.EntityMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityKindResolutionTest {

    private final MetadataResolver resolver = new MetadataResolver();

    @Test
    void standardCatalogInfersKindAndInheritedFields() {
        EntityMetadataInfo meta = resolver.resolve(Catalog.class);

        assertThat(meta.getEntityKind()).isEqualTo(EntityKind.CATALOG);
        assertThat(meta.getFormFields()).extracting(FieldMetadataInfo::getName)
            .containsExactly("code", "name");
    }

    @Test
    void standardDocumentInfersKindAndInheritedFields() {
        EntityMetadataInfo meta = resolver.resolve(Document.class);

        assertThat(meta.getEntityKind()).isEqualTo(EntityKind.DOCUMENT);
        assertThat(meta.getFormFields()).extracting(FieldMetadataInfo::getName)
            .containsExactly("number", "date");
    }

    @Test
    void directBaseEntityDefaultsToPlain() {
        assertThat(resolver.resolve(Plain.class).getEntityKind()).isEqualTo(EntityKind.PLAIN);
    }

    @Test
    void directBaseEntityCanDeclareNonStandardKind() {
        assertThat(resolver.resolve(Register.class).getEntityKind())
            .isEqualTo(EntityKind.INFORMATION_REGISTER);
    }

    @Test
    void explicitKindConflictingWithStandardBaseFailsFast() {
        assertThatThrownBy(() -> resolver.resolve(ConflictingDocument.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Entity kind conflict")
            .hasMessageContaining("CATALOG")
            .hasMessageContaining("DOCUMENT");
    }

    @EntityMetadata(listFormTitle = "Справочник")
    static class Catalog extends StandardCatalogEntity {
    }

    @EntityMetadata(listFormTitle = "Документ")
    static class Document extends StandardDocumentEntity {
    }

    @EntityMetadata(listFormTitle = "Обычная")
    static class Plain extends BaseEntity {
    }

    @EntityMetadata(
        kind = EntityKind.INFORMATION_REGISTER,
        listFormTitle = "Записи регистра"
    )
    static class Register extends BaseEntity {
    }

    @EntityMetadata(
        kind = EntityKind.CATALOG,
        listFormTitle = "Конфликт"
    )
    static class ConflictingDocument extends StandardDocumentEntity {
    }
}
