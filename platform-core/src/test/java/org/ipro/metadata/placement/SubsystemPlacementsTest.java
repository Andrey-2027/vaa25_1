package org.ipro.metadata.placement;

import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.ManagedEntityTypes;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SubsystemRegistry;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.Subsystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubsystemPlacementsTest {
    @Subsystem(title = "Primary")
    interface Primary { }

    @Subsystem(title = "Secondary")
    interface Secondary { }

    @EntityMetadata(subsystem = Primary.class, alsoIn = Secondary.class)
    static class Placed { }

    @EntityMetadata(subsystem = Primary.class)
    static class PrimaryOnly { }

    @EntityMetadata(subsystem = Primary.class, alsoIn = String.class)
    static class Unknown { }

    @EntityMetadata(subsystem = Primary.class, alsoIn = Subsystem.NoSubsystem.class)
    static class Sentinel { }

    @EntityMetadata(subsystem = Primary.class, alsoIn = {Secondary.class, Secondary.class})
    static class Duplicate { }

    @EntityMetadata(subsystem = Primary.class, alsoIn = Primary.class)
    static class RepeatedPrimary { }

    @EntityMetadata(alsoIn = Secondary.class)
    static class MissingPrimary { }

    @Test
    void additionalPlacementDoesNotChangePrimaryMembership() {
        var registry = registry(Placed.class, PrimaryOnly.class);
        registry.rebuild();
        var primary = registry.findByMarker(Primary.class).orElseThrow();
        var secondary = registry.findByMarker(Secondary.class).orElseThrow();
        assertThat(primary.getOwnEntities()).extracting(EntityMetadataInfo::getEntityClass)
            .containsExactlyInAnyOrder(Placed.class, PrimaryOnly.class);
        assertThat(primary.getAdditionalEntities()).isEmpty();
        assertThat(secondary.getOwnEntities()).isEmpty();
        assertThat(secondary.getEntityGroupsRecursive()).isEmpty();
        assertThat(secondary.getAdditionalEntities()).singleElement().satisfies(info -> {
            assertThat(info.getSubsystem()).isEqualTo(Primary.class);
            assertThat(info.getAdditionalSubsystems()).containsExactly(Secondary.class);
            assertThat(info.getPropertyOrigin("alsoIn")).isEqualTo(FactOrigin.EXPLICIT);
        });
        assertThat(primary.getOwnEntities()).filteredOn(info -> info.getEntityClass() == PrimaryOnly.class)
            .singleElement().satisfies(info ->
                assertThat(info.getPropertyOrigin("alsoIn")).isEqualTo(FactOrigin.PLATFORM_DEFAULT));
    }

    @ParameterizedTest
    @CsvSource({
        "Unknown, not a registered @Subsystem",
        "Sentinel, NoSubsystem is not a placement",
        "Duplicate, repeated marker",
        "RepeatedPrimary, repeats the primary subsystem",
        "MissingPrimary, a primary subsystem is required"
    })
    void rejectsInvalidPlacements(String type, String diagnostic) throws ClassNotFoundException {
        var registry = registry(Class.forName(getClass().getName() + "$" + type));
        assertThatThrownBy(registry::rebuild).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(type).hasMessageContaining("alsoIn").hasMessageContaining(diagnostic);
    }

    private static SubsystemRegistry registry(Class<?>... types) {
        return new SubsystemRegistry(SubsystemPlacementsTest.class.getPackageName(),
            new ManagedEntityTypes(List.of(types)), new MetadataResolver());
    }
}

