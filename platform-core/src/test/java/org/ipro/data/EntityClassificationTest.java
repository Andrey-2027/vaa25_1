package org.ipro.data;

import jakarta.persistence.Entity;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.crud.InternedEntity;
import org.ipro.metadata.*;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;
import java.lang.annotation.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityClassificationTest {
    private final MetadataResolver resolver = new MetadataResolver();
    private EntityDescriptorCatalog catalog(Class<?> type, EntityClassificationContributor... sources) {
        var managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(Set.of(type));
        return new EntityDescriptorCatalog(managed, mock(SectionMetadataRegistry.class), resolver,
            List.of(), List.of(), List.of(sources));
    }
    private static EntityClassificationContributor source(EntityKind kind) {
        return type -> Optional.of(new EntityClassificationContributor.Contribution(kind,
            FactOrigin.DERIVED, "validated:" + type.getName(), false));
    }
    @Test void standardKindAndOriginArePreserved() {
        var fact = catalog(Catalog.class).classificationOf(Catalog.class);
        assertThat(fact.kind()).isEqualTo(EntityKind.CATALOG);
        assertThat(fact.origin()).isEqualTo(FactOrigin.DERIVED);
        assertThat(fact.symbol()).isEqualTo(StandardCatalogEntity.class.getName());
        assertThat(fact.identitySymbol()).isEmpty();
    }
    @Test void interningIsOrthogonalToKindAndWrites() {
        for (Class<?> type : List.of(InternedCatalog.class, InternedPlain.class)) {
            var descriptors = catalog(type);
            var fact = descriptors.classificationOf(type);
            assertThat(fact.interned()).isTrue();
            assertThat(fact.identityOrigin()).isEqualTo(FactOrigin.DERIVED);
            assertThat(fact.identitySymbol()).isEqualTo(InternedEntity.class.getName());
            assertThat(descriptors.descriptorOf(type).capabilities().writes())
                .containsExactlyInAnyOrder(DataOperation.CREATE, DataOperation.UPDATE, DataOperation.DELETE);
        }
        assertThat(catalog(InternedCatalog.class).classificationOf(InternedCatalog.class).kind()).isEqualTo(EntityKind.CATALOG);
        assertThat(catalog(InternedPlain.class).classificationOf(InternedPlain.class).kind()).isEqualTo(EntityKind.PLAIN);
    }
    @Test void validatedContributionPromotesLegacyPlainWithoutMutatingRawMetadata() {
        var fact = catalog(Candidate.class, source(EntityKind.INFORMATION_REGISTER)).classificationOf(Candidate.class);
        assertThat(fact.kind()).isEqualTo(EntityKind.INFORMATION_REGISTER);
        assertThat(fact.symbol()).isEqualTo("validated:" + Candidate.class.getName());
        assertThat(resolver.resolve(Candidate.class).getEntityKind()).isEqualTo(EntityKind.PLAIN);
    }
    @Test void candidateWithoutSourceFailsInsteadOfPlain() {
        assertThatThrownBy(() -> catalog(Candidate.class)).hasMessageContaining("Missing validated", "CLASSIFICATION_SOURCE_MISSING").hasMessageContaining(Candidate.class.getName());
    }
    @Test void explicitlyDeclaredRegisterRequiresDescriptor() {
        assertThatThrownBy(() -> catalog(Declared.class)).hasMessageContaining("Missing validated INFORMATION_REGISTER");
    }
    @Test void conflictingCatalogOrDocumentFails() {
        for (Class<?> type : List.of(Catalog.class, Document.class))
            assertThatThrownBy(() -> catalog(type, source(EntityKind.INFORMATION_REGISTER)))
                .hasMessageContaining("kind conflict", "ENTITY_CLASSIFICATION_CONFLICT").hasMessageContaining(type.getName());
    }
    @Test void duplicateSourcesNeverChooseAWinner() {
        assertThatThrownBy(() -> catalog(Candidate.class, source(EntityKind.INFORMATION_REGISTER), source(EntityKind.INFORMATION_REGISTER)))
            .hasMessageContaining("Duplicate classification", "CLASSIFICATION_SOURCE_DUPLICATE");
        assertThatThrownBy(() -> catalog(Candidate.class, source(EntityKind.CATALOG), source(EntityKind.DOCUMENT)))
            .hasMessageContaining("Duplicate classification", "CLASSIFICATION_SOURCE_DUPLICATE");
    }
    @Test void sourceFailureIsNotAbsence() {
        var failure = new IllegalStateException("broken descriptor");
        assertThatThrownBy(() -> catalog(Candidate.class, type -> { throw failure; })).hasCause(failure).hasMessageContaining(Candidate.class.getName(), "CLASSIFICATION_SOURCE_FAILED");
    }
    @Test void nullSourceResultFails() {
        assertThatThrownBy(() -> catalog(Candidate.class, type -> null)).hasMessageContaining("source returned null");
    }
    @Test void contextsNeverMixOrInventUnknownTypes() {
        var first = catalog(Catalog.class);
        var second = catalog(Document.class);
        assertThatThrownBy(() -> first.classificationOf(Document.class)).hasMessageContaining("not managed");
        assertThat(second.classificationOf(Document.class).kind()).isEqualTo(EntityKind.DOCUMENT);
        assertThatThrownBy(() -> second.classificationOf(Catalog.class)).hasMessageContaining("not managed");
    }
    @Test void sameTypeInTwoContextsHasIndependentFactsAndUnknownSymbolsStayUnknown() {
        var first = catalog(InternedPlain.class, source(EntityKind.CATALOG));
        var second = catalog(InternedPlain.class, type -> Optional.of(
            new EntityClassificationContributor.Contribution(EntityKind.DOCUMENT, FactOrigin.UNKNOWN, "", false)));
        assertThat(first.classificationOf(InternedPlain.class).kind()).isEqualTo(EntityKind.CATALOG);
        var fact = second.classificationOf(InternedPlain.class);
        assertThat(fact.kind()).isEqualTo(EntityKind.DOCUMENT);
        assertThat(fact.origin()).isEqualTo(FactOrigin.UNKNOWN);
        assertThat(fact.symbol()).isEmpty();
    }
    @Test void autoIsNotAnEffectiveContribution() {
        assertThatThrownBy(() -> source(EntityKind.AUTO).contribute(Catalog.class)).isInstanceOf(IllegalArgumentException.class);
    }
    @Entity static class Catalog extends StandardCatalogEntity { }
    @Entity static class InternedCatalog extends StandardCatalogEntity implements InternedEntity {
        public String interningKey() { return "test"; }
    }
    @Entity @EntityMetadata static class InternedPlain implements InternedEntity {
        public String interningKey() { return "test"; }
    }
    @Entity @EntityMetadata(kind=EntityKind.DOCUMENT) static class Document { }
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    @EntityKindCandidate(EntityKind.INFORMATION_REGISTER) @interface RegisterHint { }
    @Entity @EntityMetadata @RegisterHint static class Candidate { }
    @Entity @EntityMetadata(kind=EntityKind.INFORMATION_REGISTER) static class Declared { }
}
