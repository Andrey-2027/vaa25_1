package org.ipro.data;

import jakarta.persistence.Entity;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntityDescriptorOriginTest {

    @Test
    void exposureOriginSeparatesMetadataInferenceFromApplicationRegistration() {
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(Set.of(MetadataRoot.class, RegisteredRoot.class));
        SectionMetadataRegistry sections = mock(SectionMetadataRegistry.class);
        when(sections.findByRow(MetadataRoot.class)).thenReturn(Optional.empty());
        when(sections.findByRow(RegisteredRoot.class)).thenReturn(Optional.empty());

        EntityDescriptorCatalog catalog = new EntityDescriptorCatalog(
            managed, sections, new MetadataResolver(),
            List.of(new EntityExposureOverride(RegisteredRoot.class,
                EntityExposure.INTERNAL_STORE, void.class, "registered for this fixture")));

        EntityDescriptor inferred = catalog.descriptorOf(MetadataRoot.class);
        assertThat(inferred.exposure()).isEqualTo(EntityExposure.STANDARD_ROOT);
        assertThat(inferred.exposureOrigin()).isEqualTo(FactOrigin.DERIVED);
        assertThat(inferred.exposureSymbol()).isEqualTo(MetadataRoot.class.getName());

        EntityDescriptor registered = catalog.descriptorOf(RegisteredRoot.class);
        assertThat(registered.exposure()).isEqualTo(EntityExposure.INTERNAL_STORE);
        assertThat(registered.exposureOrigin()).isEqualTo(FactOrigin.REGISTRATION);
        assertThat(registered.exposureSymbol()).isEmpty();
        assertThat(registered.reason()).isEqualTo("registered for this fixture");
    }

    @Entity
    @EntityMetadata
    static class MetadataRoot extends BaseEntity {
    }

    @Entity
    @EntityMetadata
    static class RegisteredRoot extends BaseEntity {
    }
}
