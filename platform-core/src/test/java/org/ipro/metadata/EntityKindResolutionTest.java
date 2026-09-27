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
        assertThat(meta.getEntityKindOrigin()).isEqualTo(FactOrigin.DERIVED);
        assertThat(meta.getEntityKindSymbol()).isEqualTo(StandardCatalogEntity.class.getName());
        assertThat(meta.getFormFields()).extracting(FieldMetadataInfo::getName)
            .containsExactly("code", "name");
        assertThat(meta.getFieldByName("code").getField().getDeclaringClass())
            .isEqualTo(StandardCatalogEntity.class);
    }

    @Test
    void standardDocumentInfersKindAndInheritedFields() {
        EntityMetadataInfo meta = resolver.resolve(Document.class);

        assertThat(meta.getEntityKind()).isEqualTo(EntityKind.DOCUMENT);
        assertThat(meta.getEntityKindOrigin()).isEqualTo(FactOrigin.DERIVED);
        assertThat(meta.getEntityKindSymbol()).isEqualTo(StandardDocumentEntity.class.getName());
        assertThat(meta.getFormFields()).extracting(FieldMetadataInfo::getName)
            .containsExactly("number", "date");
    }

    @Test
    void directBaseEntityDefaultsToPlain() {
        EntityMetadataInfo meta = resolver.resolve(Plain.class);
        assertThat(meta.getEntityKind()).isEqualTo(EntityKind.PLAIN);
        assertThat(meta.getEntityKindOrigin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
        assertThat(meta.getEntityKindSymbol()).isEmpty();
    }

    @Test
    void directBaseEntityCanDeclareNonStandardKind() {
        EntityMetadataInfo meta = resolver.resolve(Register.class);
        assertThat(meta.getEntityKind()).isEqualTo(EntityKind.INFORMATION_REGISTER);
        assertThat(meta.getEntityKindOrigin()).isEqualTo(FactOrigin.EXPLICIT);
        assertThat(meta.getEntityKindSymbol()).isEqualTo(Register.class.getName());
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
