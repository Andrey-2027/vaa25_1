package org.ipro.data;

import jakarta.persistence.Entity;
import org.ipro.crud.BaseEntity;
import org.ipro.fetch.plan.FetchScenario;
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

    /**
     * E3.2.0 шаг 2: набор сценариев — своя ось происхождения. Проверяется то, что до шага
     * было неразличимо: «это правило экспозиции» и «так решило приложение» при одинаковом
     * составе набора читались бы одной строкой.
     */
    @Test
    void capabilityOriginSeparatesExposureRuleFromApplicationPolicy() {
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(Set.of(RuleRoot.class, DeclaredRoot.class));
        SectionMetadataRegistry sections = mock(SectionMetadataRegistry.class);
        when(sections.findByRow(RuleRoot.class)).thenReturn(Optional.empty());
        when(sections.findByRow(DeclaredRoot.class)).thenReturn(Optional.empty());

        // Набор объявлен приложением ровно тем же составом, что дало бы правило: проверка
        // «набор, равный правилу, — всё равно объявление».
        EntityDescriptorCatalog catalog = new EntityDescriptorCatalog(
            managed, sections, new MetadataResolver(), List.of(),
            List.of(new EntityCapabilityOverride(DeclaredRoot.class,
                Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP), Set.of(),
                "набор immutable, состав ведёт канонизация")));

        EntityDescriptor byRule = catalog.descriptorOf(RuleRoot.class);
        assertThat(byRule.capabilitiesOrigin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
        assertThat(byRule.capabilitiesSymbol()).isEmpty();
        assertThat(byRule.capabilities().readScenarios())
            .containsExactlyInAnyOrder(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP);

        EntityDescriptor declared = catalog.descriptorOf(DeclaredRoot.class);
        assertThat(declared.capabilitiesOrigin()).isEqualTo(FactOrigin.REGISTRATION);
        assertThat(declared.capabilitiesSymbol()).isEmpty();
        assertThat(declared.capabilities().readScenarios())
            .as("совпадение с правилом не превращает объявление в правило")
            .containsExactlyInAnyOrder(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP);
        assertThat(declared.capabilities().reason())
            .isEqualTo("набор immutable, состав ведёт канонизация");
    }

    /**
     * Пустой набор — тоже объявление: тип, которому приложение явно не выдало ни одного
     * сценария, отличается от типа, которому их не выдало правило.
     */
    @Test
    void applicationCanDeclareAnEmptyScenarioSet() {
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(Set.of(ClosedRoot.class));
        SectionMetadataRegistry sections = mock(SectionMetadataRegistry.class);
        when(sections.findByRow(ClosedRoot.class)).thenReturn(Optional.empty());

        EntityDescriptorCatalog catalog = new EntityDescriptorCatalog(
            managed, sections, new MetadataResolver(), List.of(),
            List.of(new EntityCapabilityOverride(ClosedRoot.class, Set.of(), Set.of(),
                "read запрещён: тип обслуживается своим путём")));

        EntityDescriptor closed = catalog.descriptorOf(ClosedRoot.class);
        assertThat(closed.capabilities().readScenarios()).isEmpty();
        assertThat(closed.capabilitiesOrigin()).isEqualTo(FactOrigin.REGISTRATION);
        assertThat(closed.capabilities().reason())
            .isEqualTo("read запрещён: тип обслуживается своим путём");
    }

    /** Тип вне каталога: отказ canonical path — платформенное правило, а не пустая policy. */
    @Test
    void unclassifiedTypeTakesItsEmptySetFromThePlatformRule() {
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(Set.of());

        EntityDescriptorCatalog catalog = new EntityDescriptorCatalog(managed,
            mock(SectionMetadataRegistry.class), new MetadataResolver(), List.of());

        EntityDescriptor unclassified = catalog.descriptorOf(MetadataRoot.class);
        assertThat(unclassified.capabilities().readScenarios()).isEmpty();
        assertThat(unclassified.capabilitiesOrigin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
        assertThat(unclassified.capabilitiesSymbol()).isEmpty();
    }

    @Entity
    @EntityMetadata
    static class MetadataRoot extends BaseEntity {
    }

    @Entity
    @EntityMetadata
    static class RegisteredRoot extends BaseEntity {
    }

    @Entity
    @EntityMetadata
    static class RuleRoot extends BaseEntity {
    }

    @Entity
    @EntityMetadata
    static class DeclaredRoot extends BaseEntity {
    }

    @Entity
    @EntityMetadata
    static class ClosedRoot extends BaseEntity {
    }
}
