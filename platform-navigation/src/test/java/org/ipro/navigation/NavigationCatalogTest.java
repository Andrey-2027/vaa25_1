package org.ipro.navigation;

import org.ipro.metadata.SubsystemRegistry;
import org.ipro.navigation.fixture.original.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NavigationCatalogTest {
    static SubsystemRegistry registry(String packageName) {
        SubsystemRegistry registry = new SubsystemRegistry(packageName);
        registry.afterPropertiesSet();
        return registry;
    }

    static SubsystemRegistry original() {
        return registry("org.ipro.navigation.fixture.original");
    }

    static NavigationDefaultsContributor declarations(boolean rootVisible, boolean itemVisible) {
        return defaults -> defaults
            .subsystem(Fixtures.Root.class, "root", rootVisible)
            .subsystem(Fixtures.Child.class, "child")
            .subsystem(Fixtures.Empty.class, "empty")
            .list(Fixtures.Root.class, Fixtures.RootItem.class, "root-item", itemVisible)
            .list(Fixtures.Child.class, Fixtures.First.class, "first")
            .list(Fixtures.Child.class, Fixtures.Second.class, "second");
    }

    static NavigationCatalog catalog() {
        return NavigationCatalog.build(original(), List.of(declarations(true, true)));
    }

    @Test
    void projectsReadyMetadataWithoutMutationAndPreservesOrderIncludingEmptyNodes() {
        SubsystemRegistry registry = original();
        var before = registry.getRoots();
        var root = registry.findByMarker(Fixtures.Root.class).orElseThrow();
        var entities = new ArrayList<>(root.getOwnEntities());
        var children = new ArrayList<>(root.getChildren());
        NavigationCatalog catalog = NavigationCatalog.build(registry, List.of(declarations(true, true)));

        assertThat(catalog.roots()).extracting(NavigationNode::id).containsExactly("empty", "root");
        var projected = catalog.findSubsystem("root").orElseThrow();
        assertThat(projected.title()).isEqualTo(root.getTitle());
        assertThat(projected.icon()).isEqualTo(root.getIcon());
        assertThat(projected.order()).isEqualTo(root.getOrder());
        assertThat(projected.items()).extracting(NavigationItem::entityClass)
            .containsExactly(Fixtures.RootItem.class);
        assertThat(catalog.findSubsystem("child").orElseThrow().items())
            .extracting(NavigationItem::id).containsExactly("first", "second");
        assertThat(catalog.findItem("root-item").orElseThrow().title()).isEqualTo("Root list");
        assertThat(registry.getRoots()).isSameAs(before);
        assertThat(root.getOwnEntities()).containsExactlyElementsOf(entities);
        assertThat(root.getChildren()).containsExactlyElementsOf(children);
        assertThat(catalog.keys()).containsExactlyInAnyOrder("subsystem:root", "subsystem:child",
            "subsystem:empty", "item:root-item", "item:first", "item:second");
    }

    @Test
    void declarationOrderDoesNotChangeResult() {
        NavigationDefaultsContributor subsystems = d -> d.subsystem(Fixtures.Root.class, "root")
            .subsystem(Fixtures.Child.class, "child").subsystem(Fixtures.Empty.class, "empty");
        NavigationDefaultsContributor items = d -> d.list(Fixtures.Child.class, Fixtures.Second.class, "second")
            .list(Fixtures.Root.class, Fixtures.RootItem.class, "root-item")
            .list(Fixtures.Child.class, Fixtures.First.class, "first");
        assertThat(NavigationCatalog.build(original(), List.of(subsystems, items)).roots())
            .isEqualTo(NavigationCatalog.build(original(), List.of(items, subsystems)).roots());
    }

    @Test
    void missingSubsystemIdentityIsRejected() {
        assertThatThrownBy(() -> NavigationCatalog.build(original(), List.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing navigation ID for subsystem");
    }

    @Test
    void missingItemIdentityIsRejected() {
        assertThatThrownBy(() -> NavigationCatalog.build(original(), List.of(d -> d
            .subsystem(Fixtures.Root.class, "root").subsystem(Fixtures.Empty.class, "empty")
            .subsystem(Fixtures.Child.class, "child"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing navigation ID for list");
    }

    @Test
    void unknownSubsystemIsRejected() {
        assertThatThrownBy(() -> NavigationCatalog.build(original(), List.of(declarations(true, true),
            d -> d.subsystem(String.class, "unknown"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown navigation declarations");
    }

    @Test
    void declarationCannotInventMembership() {
        assertThatThrownBy(() -> NavigationCatalog.build(original(), List.of(declarations(true, true),
            d -> d.list(Fixtures.Root.class, Fixtures.First.class, "invented"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown navigation declarations");
    }

    @Test
    void duplicateTargetsAndIdsAreRejectedInEitherContributorOrder() {
        for (boolean reverse : List.of(false, true)) {
            NavigationDefaultsContributor original = declarations(true, true);
            List<NavigationDefaultsContributor> duplicates = List.of(
                d -> d.subsystem(Fixtures.Root.class, "other-root"),
                d -> d.subsystem(String.class, "root"),
                d -> d.list(Fixtures.Root.class, Fixtures.RootItem.class, "other-item"),
                d -> d.list(Fixtures.Root.class, String.class, "root-item"));
            for (var duplicate : duplicates) {
                var contributors = reverse ? List.of(duplicate, original) : List.of(original, duplicate);
                assertThatThrownBy(() -> NavigationCatalog.build(original(), contributors))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Upper", "a_b", "a.b", "-a", "a-", "a--b", " a", "имя"})
    void invalidLiteralIdsAreRejected(String id) {
        assertThatThrownBy(() -> NavigationCatalog.build(original(), List.of(d ->
            d.subsystem(Fixtures.Root.class, id))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lower-kebab-case");
    }

    @Test
    void sourceAndResolvedCollectionsAreImmutableAndRetainedBuilderIsFrozen() {
        AtomicReference<NavigationDefaults> retained = new AtomicReference<>();
        NavigationCatalog catalog = NavigationCatalog.build(original(), List.of(d -> {
            retained.set(d);
            declarations(true, true).contribute(d);
        }));
        assertThatThrownBy(() -> catalog.roots().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.keys().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.findSubsystem("root").orElseThrow().items().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.findSubsystem("root").orElseThrow().children().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> retained.get().subsystem(String.class, "late"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("frozen");
    }
}
