package org.ip.navigation;

import org.ipro.navigation.NavigationCatalog;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationFunctions;
import org.ipro.navigation.NavigationStructure;
import org.navigationfixture.Fixtures;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class NavigationCatalogFixtures {
    private NavigationCatalogFixtures() {}

    public static NavigationCatalog nested() {
        return catalog("root", "Root", "child", List.of(
            list("root-item", Fixtures.RootItem.class, "Root list", "root"),
            list("child-item", Fixtures.ChildItem.class, "Child list", "child")));
    }

    static NavigationFunction list(String key, Class<?> type, String title, String area) {
        return new NavigationFunction("list:" + key, type, NavigationFunction.Kind.LIST, title, "", 0, area);
    }

    static NavigationCatalog catalog(String root, String title, String child, List<NavigationFunction> functions) {
        var top = new NavigationStructure.Section(root, null, title, "BOOK", 0, root);
        var sections = child == null ? List.of(top) : List.of(top,
            new NavigationStructure.Section(child, root, "Child", "", 0, child));
        var entries = functions.stream().map(function -> new NavigationStructure.Entry(
            function.key().substring("list:".length()), function.areaKey(), function.key(), null, 0)).toList();
        return NavigationCatalog.build(new NavigationStructure(0, Instant.EPOCH, sections, entries), new NavigationFunctions() {
            @Override public List<NavigationFunction> all() { return functions; }
            @Override public Optional<NavigationFunction> find(String key) {
                return functions.stream().filter(function -> function.key().equals(key)).findFirst();
            }
        });
    }
}
