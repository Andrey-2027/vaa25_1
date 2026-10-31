package org.ipro.metadata;

import org.ipro.crud.BaseEntity;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.data.SearchFieldResolver;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetadataApplicabilityTest {
    private final MetadataResolver resolver = new MetadataResolver();

    @Test
    void absenceIsOptionalButStrictResolutionStillRejectsIt() {
        assertThat(resolver.find(Internal.class)).isEmpty();
        assertThatThrownBy(() -> resolver.resolve(Internal.class))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(Internal.class.getName());
        assertThat(resolver.find(Valid.class)).isPresent();
    }

    @Test
    void declaredErrorsRemainErrorsAndAreNotCachedAsAbsence() {
        for (Class<?> type : List.of(BrokenSort.class, BrokenReference.class, Conflict.class)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> resolver.find(type)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(type.getName()).hasCauseInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void descriptorFallbackIsReservedForInternalTypes() {
        var managed = mock(ManagedEntityCatalog.class);
        var sections = mock(SectionMetadataRegistry.class);
        when(managed.managedEntityClasses()).thenReturn(Set.of(Internal.class));
        assertThat(new EntityDescriptorCatalog(managed, sections, resolver, List.of())
            .descriptorOf(Internal.class).exposure()).isEqualTo(EntityExposure.INTERNAL_STORE);
        when(managed.managedEntityClasses()).thenReturn(Set.of(Internal.class, BrokenSort.class));
        assertThatThrownBy(() -> new EntityDescriptorCatalog(managed, sections, resolver, List.of()))
            .hasMessageContaining("displaySortFields").hasMessageContaining("missing");
    }

    @Test
    void searchAndReferenceDeepeningDoNotSuppressTargetErrors() {
        var search = new SearchFieldResolver(resolver);
        assertThat(search.resolve(Internal.class, List.of())).isEmpty();
        assertThatThrownBy(() -> search.resolve(BrokenSort.class, List.of()))
            .hasMessageContaining("displaySortFields");
        assertThatThrownBy(() -> FetchGraphs.deepen(Holder.class, List.of("target"), resolver))
            .hasMessageContaining(BrokenSort.class.getName()).hasMessageContaining("missing");
    }

    @Test
    void startupCollectsRootErrorsAndDoesNotPublishAHealthySnapshot() {
        var diagnostics = MetadataConsistencyValidator.validate(resolver,
            List.of(BrokenSort.class, Conflict.class, Internal.class), List.of());
        assertThat(diagnostics).filteredOn(d -> d.code().equals("INVALID_ENTITY_METADATA")).hasSize(2);
        assertThatThrownBy(() -> new MetadataConsistencyStartupCheck(
            List.of(BrokenSort.class, Conflict.class), resolver, List.of()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(BrokenSort.class.getName())
            .hasMessageContaining(Conflict.class.getName()).hasMessageContaining("displaySortFields");
    }

    static class Internal extends BaseEntity {}
    @EntityMetadata static class Valid extends BaseEntity {}
    @EntityMetadata(displaySortFields = "missing") static class BrokenSort extends BaseEntity {}
    @EntityMetadata static class BrokenReference extends BaseEntity {
        @FieldMetadata(lookup = @Lookup(entity = Valid.class)) Internal target;
    }
    @EntityMetadata(kind = EntityKind.CATALOG) static class Conflict extends StandardDocumentEntity {}
    static class Holder extends BaseEntity {
        @FieldMetadata(type = FieldType.ENTITY_REFERENCE) BrokenSort target;
    }
}
