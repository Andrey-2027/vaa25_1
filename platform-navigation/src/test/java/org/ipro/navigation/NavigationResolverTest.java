package org.ipro.navigation;

import org.ipro.navigation.fixture.original.Fixtures;
import org.ipro.navigation.fixture.renamed.Renamed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
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
    void profileOverrideHidesAnItemAndShowEqualsDefault(NavigationVisibility visibility) {
        var tree = resolver.resolve(NavigationCatalogTest.catalog(), Map.of("entry:root-item", visibility), type -> true);
        boolean expected = visibility != NavigationVisibility.HIDE;
        assertThat(tree.findSubsystem("root").orElseThrow().items().isEmpty()).isEqualTo(!expected);
    }

    @Test
    void showStillChecksReads() {
        var catalog = NavigationCatalogTest.catalog();
        assertThat(resolver.resolve(catalog, Map.of("section:root", NavigationVisibility.SHOW),
            type -> true).roots()).hasSize(1);
        assertThat(resolver.resolve(catalog, Map.of("section:root", NavigationVisibility.SHOW),
            type -> false).roots()).isEmpty();
    }

    @Test
    void explicitShowCannotBypassHiddenAncestorAndDoesNotInvokeReadGate() {
        AtomicInteger reads = new AtomicInteger();
        var tree = resolver.resolve(NavigationCatalogTest.catalog(), Map.of(
            "section:root", NavigationVisibility.HIDE, "section:child", NavigationVisibility.SHOW,
            "entry:first", NavigationVisibility.SHOW), type -> { reads.incrementAndGet(); return true; });
        assertThat(tree.roots()).isEmpty();
        assertThat(reads.get()).isZero();
    }

    @Test
    void hiddenChildAndHiddenItemAlsoDisappearFromParentPage() {
        var catalog = NavigationCatalogTest.catalog();
        var childHidden = resolver.resolve(catalog, Map.of("section:child", NavigationVisibility.HIDE), t -> true);
        assertThat(childHidden.findSubsystem("child")).isEmpty();
        assertThat(childHidden.findSubsystem("root").orElseThrow().groupsRecursive())
            .extracting(NavigationTree.Group::subsystemId).containsExactly("root");
        var itemHidden = resolver.resolve(catalog, Map.of("entry:first", NavigationVisibility.HIDE), t -> true);
        assertThat(itemHidden.findSubsystem("root").orElseThrow().groupsRecursive().get(1).items())
            .extracting(NavigationItem::id).containsExactly("second");
    }

    @Test
    void deniedReadWinsOverShowAndEmptyGroupsAreRemoved() {
        var tree = resolver.resolve(NavigationCatalogTest.catalog(), Map.of("entry:root-item", NavigationVisibility.SHOW),
            item -> item.entityClass() != Fixtures.RootItem.class);
        assertThat(tree.findSubsystem("root").orElseThrow().groupsRecursive())
            .extracting(NavigationTree.Group::subsystemId).containsExactly("child");
        assertThat(resolver.resolve(NavigationCatalogTest.catalog(), Map.of(), type -> false).roots()).isEmpty();
    }

    @Test
    void staleKeysAreReportedWithoutMutatingProfileAndResultIsIsolatedBetweenUsers() {
        Map<String, NavigationVisibility> overrides = new HashMap<>(Map.of(
            "entry:removed", NavigationVisibility.HIDE, "entry:first", NavigationVisibility.HIDE));
        var catalog = NavigationCatalogTest.catalog();
        var first = resolver.resolve(catalog, overrides, type -> true);
        overrides.clear();
        var second = resolver.resolve(catalog, overrides, type -> true);
        assertThat(first.staleKeys()).containsExactly("entry:removed");
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
    void itemKeySurvivesClassRenamePackageTitleAndPlacementChangesWhenTheKeySourceKeepsIt() {
        var renamed = renamedCatalog("root-item");
        var profile = Map.of("entry:root-item", NavigationVisibility.HIDE);
        assertThat(resolver.resolve(NavigationCatalogTest.catalog(), profile, t -> true)
            .findSubsystem("root").orElseThrow().items()).isEmpty();
        var result = resolver.resolve(renamed, profile, t -> true);
        assertThat(result.roots()).isEmpty();
        assertThat(result.staleKeys()).isEmpty();
        assertThat(resolver.resolve(renamed, Map.of(), t -> true).findSubsystem("destination").orElseThrow().items())
            .extracting(NavigationItem::id).containsExactly("root-item");
    }

    @Test
    void itemKeyChangesWithoutAliasAndOldSettingBecomesStale() {
        var renamed = renamedCatalog("renamed-item");
        var result = resolver.resolve(renamed, Map.of("entry:root-item", NavigationVisibility.HIDE), t -> true);
        assertThat(result.staleKeys()).containsExactly("entry:root-item");
        assertThat(result.findSubsystem("destination").orElseThrow().items()).hasSize(1);
    }
    private static NavigationCatalog renamedCatalog(String placement) {
        return NavigationCatalog.build(new NavigationStructure(0, java.time.Instant.EPOCH,
            java.util.List.of(new NavigationStructure.Section("destination", null, "Destination", "", 0, null)),
            java.util.List.of(new NavigationStructure.Entry(placement, "destination", "list:renamed-item", null, 0))),
            NavigationCatalogTest.functions(java.util.List.of(
                NavigationCatalogTest.list("renamed-item", Renamed.RenamedItem.class, "Renamed", null, 0))));
    }
}
