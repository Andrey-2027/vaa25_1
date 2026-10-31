package org.ipro.metadata;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.validation.constraints.NotNull;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.annotation.CatalogOwner;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E3.R: owner-capability разбирается из явного объявления и проверяется до старта.
 *
 * <p>Проверки адресные: каждая причина отказа — свой стабильный код, а не общий
 * «невалидные метаданные». Обычная ссылка без объявления не становится владельцем.</p>
 */
class CatalogOwnerResolverTest {

    private final CatalogOwnerResolver resolver = new CatalogOwnerResolver();

    // === допустимые модели ===

    @Entity(name = "OwnerA")
    @EntityMetadata(kind = EntityKind.CATALOG, listFormTitle = "Владельцы A")
    static class OwnerA implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "ValidChild")
    @EntityMetadata(kind = EntityKind.CATALOG, listFormTitle = "Договоры")
    @CatalogOwner(property = "owner")
    static class ValidChild implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        @NotNull
        private OwnerA owner;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "RequiredExplicitChild")
    @EntityMetadata(kind = EntityKind.CATALOG, listFormTitle = "Договоры")
    @CatalogOwner(property = "owner")
    static class RequiredExplicitChild implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        @NotNull
        @FieldMetadata(label = "Контрагент", required = RequiredMode.REQUIRED)
        private OwnerA owner;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Test
    void validDeclarationResolvesTheOwnerTargetFromTheAssociation() {
        CatalogOwnerResolution resolution = resolver.resolve(ValidChild.class);

        assertThat(resolution.diagnostics()).isEmpty();
        assertThat(resolution.owner()).isEqualTo(
            new CatalogOwnerDescriptor("owner", OwnerA.class.getName()));
    }

    @Test
    void explicitRequiredModeIsAccepted() {
        assertThat(resolver.resolve(RequiredExplicitChild.class).owner())
            .isEqualTo(new CatalogOwnerDescriptor("owner", OwnerA.class.getName()));
    }

    @Test
    void ordinaryCatalogWithoutDeclarationHasNoOwnerAndNoDiagnostics() {
        CatalogOwnerResolution resolution = resolver.resolve(OwnerA.class);

        assertThat(resolution.declared()).isFalse();
        assertThat(resolution.diagnostics()).isEmpty();
        assertThat(resolution.owner()).isNull();
    }

    // === отказы ===

    @Test
    void missingPropertyIsReported() {
        assertProblem(MissingProperty.class, MetadataDiagnosticCodes.CATALOG_OWNER_PROPERTY_MISSING,
            "owner");
    }

    @Test
    void nonAssociationFieldIsReported() {
        assertProblem(NotAnAssociation.class,
            MetadataDiagnosticCodes.CATALOG_OWNER_ASSOCIATION_INVALID, "owner");
    }

    @Test
    void collectionAssociationIsReported() {
        assertProblem(CollectionOwner.class,
            MetadataDiagnosticCodes.CATALOG_OWNER_ASSOCIATION_INVALID, "owners");
    }

    @Test
    void targetWithoutCatalogSemanticsIsReported() {
        assertProblem(PlainTargetChild.class,
            MetadataDiagnosticCodes.CATALOG_OWNER_TARGET_INVALID, "owner");
    }

    @Test
    void selfOwnerIsReported() {
        assertProblem(SelfOwner.class, MetadataDiagnosticCodes.CATALOG_OWNER_TARGET_INVALID, "owner");
    }

    @Test
    void targetWithoutLongIdentityIsReported() {
        assertProblem(NonLongTargetChild.class,
            MetadataDiagnosticCodes.CATALOG_OWNER_TARGET_INVALID, "owner");
    }

    @Test
    void optionalAssociationIsReportedAsRequiredMismatch() {
        assertProblem(OptionalOwner.class,
            MetadataDiagnosticCodes.CATALOG_OWNER_REQUIRED_MISMATCH, "owner");
    }

    @Test
    void missingExplicitJoinColumnIsReportedAsRequiredMismatch() {
        assertProblem(NoJoinColumnOwner.class,
            MetadataDiagnosticCodes.CATALOG_OWNER_REQUIRED_MISMATCH, "owner");
    }

    @Test
    void nullableJoinColumnIsReportedAsRequiredMismatch() {
        assertProblem(NullableColumnOwner.class,
            MetadataDiagnosticCodes.CATALOG_OWNER_REQUIRED_MISMATCH, "owner");
    }

    @Test
    void missingNotNullIsReportedAsRequiredMismatch() {
        assertProblem(NoNotNullOwner.class,
            MetadataDiagnosticCodes.CATALOG_OWNER_REQUIRED_MISMATCH, "owner");
    }

    @Test
    void explicitOptionalFieldMetadataIsReportedAsRequiredMismatch() {
        assertProblem(OptionalFieldMetadataOwner.class,
            MetadataDiagnosticCodes.CATALOG_OWNER_REQUIRED_MISMATCH, "owner");
    }

    @Test
    void duplicateDeclarationInHierarchyIsReported() {
        assertProblem(DuplicateChild.class, MetadataDiagnosticCodes.CATALOG_OWNER_DUPLICATE, "");
    }

    @Test
    void documentCannotDeclareOwner() {
        assertProblem(DocumentWithOwner.class,
            MetadataDiagnosticCodes.CATALOG_OWNER_SUBJECT_INVALID, "owner");
    }

    @Test
    void plainEntityCannotDeclareOwner() {
        assertProblem(PlainChildWithOwner.class,
            MetadataDiagnosticCodes.CATALOG_OWNER_SUBJECT_INVALID, "owner");
    }

    @Test
    void sectionRowCannotDeclareOwner() {
        assertProblem(SectionRowWithOwner.class,
            MetadataDiagnosticCodes.CATALOG_OWNER_SUBJECT_INVALID, "owner");
    }

    @Test
    void startupCheckStopsOnOwnerErrorAndNamesTheCode() {
        assertThatThrownBy(() -> new MetadataConsistencyStartupCheck(
                List.of(MissingProperty.class), new MetadataResolver(), List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Метаданные не согласованы")
            .hasMessageContaining(MetadataDiagnosticCodes.CATALOG_OWNER_PROPERTY_MISSING)
            .hasMessageContaining(MissingProperty.class.getName())
            .hasMessageContaining("owner");
    }

    private void assertProblem(Class<?> type, String code, String field) {
        CatalogOwnerResolution resolution = resolver.resolve(type);

        assertThat(resolution.owner()).isNull();
        assertThat(resolution.diagnostics()).hasSize(1);
        MetadataDiagnostic diagnostic = resolution.diagnostics().getFirst();
        assertThat(diagnostic.severity()).isEqualTo(MetadataDiagnostic.Severity.ERROR);
        assertThat(diagnostic.code()).isEqualTo(code);
        assertThat(diagnostic.entity()).isEqualTo(type.getName());
        assertThat(diagnostic.field()).isEqualTo(field);
        assertThat(diagnostic.source()).isEqualTo("@CatalogOwner");
    }

    // === fixtures отказов ===

    @Entity(name = "OwnerB")
    @EntityMetadata(kind = EntityKind.CATALOG, listFormTitle = "Владельцы B")
    static class OwnerB implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "MissingProperty")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @CatalogOwner(property = "owner")
    static class MissingProperty implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "NotAnAssociation")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @CatalogOwner(property = "owner")
    static class NotAnAssociation implements IdentifiableEntity<Long> {
        @Id
        private Long id;
        private String owner;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "CollectionOwner")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @CatalogOwner(property = "owners")
    static class CollectionOwner implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @OneToMany
        private List<OwnerA> owners;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "ManyToManyOwner")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @CatalogOwner(property = "owners")
    static class ManyToManyOwner implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToMany
        private List<OwnerA> owners;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "PlainTarget")
    @EntityMetadata(listFormTitle = "Обычная сущность")
    static class PlainTarget implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "PlainTargetChild")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @CatalogOwner(property = "owner")
    static class PlainTargetChild implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        @NotNull
        private PlainTarget owner;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "SelfOwner")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @CatalogOwner(property = "owner")
    static class SelfOwner implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        @NotNull
        private SelfOwner owner;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "NonLongTarget")
    @EntityMetadata(kind = EntityKind.CATALOG)
    static class NonLongTarget implements IdentifiableEntity<String> {
        @Id
        private String id;

        @Override
        public String getId() {
            return id;
        }

        @Override
        public void setId(String id) {
            this.id = id;
        }
    }

    @Entity(name = "NonLongTargetChild")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @CatalogOwner(property = "owner")
    static class NonLongTargetChild implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        @NotNull
        private NonLongTarget owner;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "OptionalOwner")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @CatalogOwner(property = "owner")
    static class OptionalOwner implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToOne(optional = true)
        @JoinColumn(name = "owner_id", nullable = false)
        @NotNull
        private OwnerA owner;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "NoJoinColumnOwner")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @CatalogOwner(property = "owner")
    static class NoJoinColumnOwner implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToOne(optional = false)
        @NotNull
        private OwnerA owner;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "NullableColumnOwner")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @CatalogOwner(property = "owner")
    static class NullableColumnOwner implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = true)
        @NotNull
        private OwnerA owner;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "NoNotNullOwner")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @CatalogOwner(property = "owner")
    static class NoNotNullOwner implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        private OwnerA owner;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "OptionalFieldMetadataOwner")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @CatalogOwner(property = "owner")
    static class OptionalFieldMetadataOwner implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        @NotNull
        @FieldMetadata(label = "Владелец", required = RequiredMode.OPTIONAL)
        private OwnerA owner;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "DuplicateParent")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @CatalogOwner(property = "ownerA")
    static class DuplicateParent implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_a_id", nullable = false)
        @NotNull
        private OwnerA ownerA;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "DuplicateChild")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @CatalogOwner(property = "ownerB")
    static class DuplicateChild extends DuplicateParent {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_b_id", nullable = false)
        @NotNull
        private OwnerB ownerB;
    }

    @Entity(name = "DocumentWithOwner")
    @EntityMetadata(kind = EntityKind.DOCUMENT)
    @CatalogOwner(property = "owner")
    static class DocumentWithOwner implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        @NotNull
        private OwnerA owner;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "PlainChildWithOwner")
    @EntityMetadata(listFormTitle = "Обычная")
    @CatalogOwner(property = "owner")
    static class PlainChildWithOwner implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        @NotNull
        private OwnerA owner;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity(name = "SectionRowWithOwner")
    @EntityMetadata(kind = EntityKind.CATALOG)
    @TableSectionMetadata(parentEntity = OwnerA.class, parentField = "owner")
    @CatalogOwner(property = "owner")
    static class SectionRowWithOwner implements IdentifiableEntity<Long> {
        @Id
        private Long id;

        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        @NotNull
        private OwnerA owner;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }
}
