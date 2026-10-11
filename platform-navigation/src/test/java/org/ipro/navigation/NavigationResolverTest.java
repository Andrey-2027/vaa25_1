package org.ipro.navigation;

import org.ipro.navigation.fixture.original.Fixtures;
import org.ipro.navigation.fixture.renamed.Renamed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NavigationResolverTest {
    private final NavigationResolver resolver = new NavigationResolver();

    @Test
    void sameProjectionDrivesChildAndParentGroupsAndRemovesEmptyNodes() {
        NavigationTree tree = resolver.resolve(NavigationCatalogTest.catalog(), Map.of(), type -> true);
        assertThat(tree.roots()).extracting(NavigationTree.Node::id).containsExactly("root");
        var root = tree.findSubsystem("root").orElseThrow();
        assertThat(root.groupsRecursive()).extracting(NavigationTree.Group::subsystemId)
            .containsExactly("root", "child");
        assertThat(root.groupsRecursive().get(1).items())
            .isEqualTo(tree.findSubsystem("child").orElseThrow().groupsRecursive().getFirst().items());
        assertThat(root.groupsRecursive().get(1).items()).extracting(NavigationItem::id)
            .containsExactly("first", "second");
        assertThatThrownBy(() -> tree.roots().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> root.children().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> root.groupsRecursive().getFirst().items().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @EnumSource(NavigationVisibility.class)
    void overridesInheritOrReplaceDeveloperDefaults(NavigationVisibility visibility) {
        for (boolean defaultVisible : List.of(false, true)) {
            var catalog = NavigationCatalog.build(NavigationCatalogTest.original(),
                List.of(NavigationCatalogTest.declarations(true, defaultVisible)));
            var tree = resolver.resolve(catalog, Map.of("item:root-item", visibility), type -> true);
            boolean expected = visibility == NavigationVisibility.SHOW
                || (visibility == NavigationVisibility.DEFAULT && defaultVisible);
            assertThat(tree.findSubsystem("root").orElseThrow().items().isEmpty()).isEqualTo(!expected);
        }
    }

    @Test
    void showingSubsystemCanReplaceDeveloperHideButStillChecksReads() {
        var catalog = NavigationCatalog.build(NavigationCatalogTest.original(),
            List.of(NavigationCatalogTest.declarations(false, true)));
        assertThat(resolver.resolve(catalog, Map.of(), type -> true).roots()).isEmpty();
        assertThat(resolver.resolve(catalog, Map.of("subsystem:root", NavigationVisibility.SHOW),
            type -> true).roots()).hasSize(1);
        assertThat(resolver.resolve(catalog, Map.of("subsystem:root", NavigationVisibility.SHOW),
            type -> false).roots()).isEmpty();
    }

    @Test
    void explicitShowCannotBypassHiddenAncestorAndDoesNotInvokeReadGate() {
        AtomicInteger reads = new AtomicInteger();
        var tree = resolver.resolve(NavigationCatalogTest.catalog(), Map.of(
            "subsystem:root", NavigationVisibility.HIDE, "subsystem:child", NavigationVisibility.SHOW,
            "item:first", NavigationVisibility.SHOW), type -> { reads.incrementAndGet(); return true; });
        assertThat(tree.roots()).isEmpty();
        assertThat(reads.get()).isZero();
    }

    @Test
    void hiddenChildAndHiddenItemAlsoDisappearFromParentPage() {
        var catalog = NavigationCatalogTest.catalog();
        var childHidden = resolver.resolve(catalog, Map.of("subsystem:child", NavigationVisibility.HIDE), t -> true);
        assertThat(childHidden.findSubsystem("child")).isEmpty();
        assertThat(childHidden.findSubsystem("root").orElseThrow().groupsRecursive())
            .extracting(NavigationTree.Group::subsystemId).containsExactly("root");
        var itemHidden = resolver.resolve(catalog, Map.of("item:first", NavigationVisibility.HIDE), t -> true);
        assertThat(itemHidden.findSubsystem("root").orElseThrow().groupsRecursive().get(1).items())
            .extracting(NavigationItem::id).containsExactly("second");
    }

    @Test
    void deniedReadWinsOverShowAndEmptyGroupsAreRemoved() {
        var tree = resolver.resolve(NavigationCatalogTest.catalog(), Map.of("item:root-item", NavigationVisibility.SHOW),
            type -> type != Fixtures.RootItem.class);
        assertThat(tree.findSubsystem("root").orElseThrow().groupsRecursive())
            .extracting(NavigationTree.Group::subsystemId).containsExactly("child");
        assertThat(resolver.resolve(NavigationCatalogTest.catalog(), Map.of(), type -> false).roots()).isEmpty();
    }

    @Test
    void staleKeysAreReportedWithoutMutatingProfileAndResultIsIsolatedBetweenUsers() {
        Map<String, NavigationVisibility> overrides = new HashMap<>(Map.of(
            "item:removed", NavigationVisibility.HIDE, "item:first", NavigationVisibility.HIDE));
        var catalog = NavigationCatalogTest.catalog();
        var first = resolver.resolve(catalog, overrides, type -> true);
        overrides.clear();
        var second = resolver.resolve(catalog, overrides, type -> true);
        assertThat(first.staleKeys()).containsExactly("item:removed");
        assertThat(first.findSubsystem("child").orElseThrow().items())
            .extracting(NavigationItem::id).containsExactly("second");
        assertThat(second.findSubsystem("child").orElseThrow().items()).hasSize(2);
        assertThat(catalog.findSubsystem("child").orElseThrow().items()).hasSize(2);
        assertThatThrownBy(() -> first.staleKeys().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void readGateFailurePropagatesInsteadOfReturningPermissiveMenu() {
        assertThatThrownBy(() -> resolver.resolve(NavigationCatalogTest.catalog(), Map.of(), type -> {
            throw new IllegalStateException("read gate failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("read gate failed");
    }

    @Test
    void explicitIdentitiesPreserveHidingAcrossClassPackageTitleAndPlacementChanges() {
        var renamedRegistry = NavigationCatalogTest.registry("org.ipro.navigation.fixture.renamed");
        var renamed = NavigationCatalog.build(renamedRegistry, List.of(d -> d
            .subsystem(Renamed.RenamedRoot.class, "root")
            .subsystem(Renamed.Destination.class, "child")
            .list(Renamed.Destination.class, Renamed.RenamedItem.class, "root-item")));
        var profile = Map.of("item:root-item", NavigationVisibility.HIDE);
        assertThat(resolver.resolve(NavigationCatalogTest.catalog(), profile, t -> true)
            .findSubsystem("root").orElseThrow().items()).isEmpty();
        var result = resolver.resolve(renamed, profile, t -> true);
        assertThat(result.roots()).isEmpty();
        assertThat(result.staleKeys()).isEmpty();
        assertThat(resolver.resolve(renamed, Map.of("subsystem:root", NavigationVisibility.HIDE), t -> true).roots())
            .isEmpty();
        assertThat(resolver.resolve(renamed, Map.of(), t -> true).findSubsystem("child").orElseThrow().items())
            .extracting(NavigationItem::id).containsExactly("root-item");
    }
}
