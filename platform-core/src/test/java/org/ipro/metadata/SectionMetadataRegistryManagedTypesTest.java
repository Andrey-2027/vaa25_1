package org.ipro.metadata;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.metadata.annotation.TableSections;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SectionMetadataRegistryManagedTypesTest {

    @Test
    void discoversOnlySectionsFromTheSharedPersistenceUnitSnapshot() throws Exception {
        ManagedEntityTypes types = new ManagedEntityTypes(List.of(SectionOwner.class, SectionRow.class));
        SectionMetadataRegistry registry = new SectionMetadataRegistry(types, new MetadataResolver());

        registry.afterPropertiesSet();

        assertThat(registry.all()).extracting(TableSectionMetadataInfo::getKey)
            .containsExactly("SectionOwner.SectionRow");
        assertThat(registry.findByRow(SectionRow.class)).isPresent();
    }

    @Test
    void rejectsSectionRowOutsideTheCurrentPersistenceUnit() {
        ManagedEntityTypes types = new ManagedEntityTypes(List.of(SectionOwner.class));
        SectionMetadataRegistry registry = new SectionMetadataRegistry(types, new MetadataResolver());

        assertThatThrownBy(registry::afterPropertiesSet)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Owned-section row must be managed by the current persistence unit")
            .hasMessageContaining(SectionRow.class.getName());
    }

    @Test
    void rejectsSectionOwnerOutsideTheCurrentPersistenceUnit() {
        ManagedEntityTypes types = new ManagedEntityTypes(List.of(SectionRow.class));
        SectionMetadataRegistry registry = new SectionMetadataRegistry(types, new MetadataResolver());

        assertThatThrownBy(registry::afterPropertiesSet)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Owned-section owner must be managed by the current persistence unit")
            .hasMessageContaining(SectionOwner.class.getName());
    }

    @Entity
    @EntityMetadata(listFormTitle = "Раздел теста")
    @TableSections(SectionRow.class)
    static class SectionOwner extends BaseEntity { }

    @Entity
    @TableSectionMetadata(parentEntity = SectionOwner.class, parentField = "owner")
    static class SectionRow extends BaseEntity {
        @ManyToOne
        private SectionOwner owner;
    }
}
