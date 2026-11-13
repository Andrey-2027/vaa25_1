package org.ipro.navigation;

import org.ipro.navigation.fixture.original.Fixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NavigationCatalogTest {
    static final List<NavigationFunction> BASE_LISTS = List.of(
        list("root-item", Fixtures.RootItem.class, "Root list", "root", 8),
        list("first", Fixtures.First.class, "Child first", "child", 1),
        list("second", Fixtures.Second.class, "Child second", "child", 2));

    static NavigationFunction list(String key, Class<?> type, String title, String area, int order) {
        return new NavigationFunction("list:" + key, type, NavigationFunction.Kind.LIST, title, "", order, area);
    }

    static NavigationFunctions functions(List<NavigationFunction> rows) {
        return new NavigationFunctions() {
            @Override public List<NavigationFunction> all() { return rows; }
            @Override public Optional<NavigationFunction> find(String key) {
                return rows.stream().filter(row -> row.key().equals(key)).findFirst();
            }
        };
    }

    static NavigationCatalog catalog() {
        return NavigationCatalog.build(new NavigationStructure(0, Instant.EPOCH, List.of(
            new NavigationStructure.Section("child", "root", "Child", "FILE", 10, "child"),
            new NavigationStructure.Section("root", null, "Root", "BOOK", 20, "root"),
            new NavigationStructure.Section("empty", null, "Empty", "", 10, null)), List.of(
            new NavigationStructure.Entry("second", "child", "list:second", null, 2),
            new NavigationStructure.Entry("first", "child", "list:first", null, 1),
            new NavigationStructure.Entry("root-item", "root", "list:root-item", null, 8))),
            functions(BASE_LISTS));
    }

    @Test void projectsStoredOrderAndPlacementKeys() {
        var catalog = catalog();
        assertThat(catalog.roots()).extracting(NavigationNode::id).containsExactly("empty", "root");
        assertThat(catalog.findSubsystem("child").orElseThrow().items())
            .extracting(NavigationItem::id).containsExactly("first", "second");
        assertThat(catalog.findItem("root-item").orElseThrow().title()).isEqualTo("Root list");
        assertThat(catalog.keys()).containsExactlyInAnyOrder("section:root", "section:child",
            "section:empty", "entry:root-item", "entry:first", "entry:second");
    }

    @Test void projectsTitleOverrideAndKeepsMissingFunctionPlacementKey() {
        var catalog = NavigationCatalog.build(new NavigationStructure(0, Instant.EPOCH,
            List.of(new NavigationStructure.Section("root", null, "Root", "", 0, null)), List.of(
            new NavigationStructure.Entry("custom-title", "root", "list:first", "Renamed", 0),
            new NavigationStructure.Entry("missing", "root", "list:missing", null, 1))), functions(BASE_LISTS));
        assertThat(catalog.findSubsystem("root").orElseThrow().items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("Renamed");
            assertThat(item.function().key()).isEqualTo("list:first");
        });
        assertThat(catalog.findItem("missing")).isEmpty();
        assertThat(catalog.keys()).contains("entry:missing");
    }
}
