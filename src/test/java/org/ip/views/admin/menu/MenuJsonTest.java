package org.ip.views.admin.menu;

import org.ipro.navigation.NavigationImportance;
import org.ipro.navigation.NavigationStructure;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MenuJsonTest {
    @Test
    void roundTripKeepsNestedStructureKeysAndImportance() {
        var original = structure("old", "child");
        var decoded = MenuJson.decode(MenuJson.encode("Технолог", original));
        var preview = MenuJson.preview(decoded, null, Set.of("list:items"));
        assertThat(preview.name()).isEqualTo("Технолог");
        assertThat(preview.structure().sections()).isEqualTo(original.sections());
        assertThat(preview.structure().entries()).isEqualTo(original.entries());
        assertThat(preview.unknown()).isEmpty();
        assertThat(preview.changes()).allMatch(row -> row.endsWith("добавится"));
    }

    @Test
    void comparesTitlesAndMovesAndReportsSkippedFunctions() {
        var old = structure("old", "child");
        var updated = structure("new", "root");
        var file = MenuJson.decode(MenuJson.encode("Технолог", updated));
        assertThat(MenuJson.preview(file, old, Set.of("list:items")).changes())
            .anyMatch(row -> row.contains("прежний заголовок"))
            .anyMatch(row -> row.contains("переместится"));
        var skipped = MenuJson.preview(file, old, Set.of());
        assertThat(skipped.structure().entries()).isEmpty();
        assertThat(skipped.unknown()).containsExactly("items — list:items");
        assertThat(skipped.changes()).anyMatch(row -> row.endsWith("исчезнет"));
    }

    @Test
    void rejectsUnsupportedVersionAndTrailingJson() {
        assertThatThrownBy(() -> MenuJson.decode("""
            {"format":"gitvaa.navigation-menu","version":2,"name":"Menu","sections":[]}
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("JSON");
        assertThatThrownBy(() -> MenuJson.decode("""
            {"format":"gitvaa.navigation-menu","version":1,"name":"Menu","sections":[]} {}
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }

    private static NavigationStructure structure(String title, String destination) {
        return new NavigationStructure(1, 3, Instant.EPOCH, List.of(
            new NavigationStructure.Section("root", null, title, "", 2, "area"),
            new NavigationStructure.Section("child", "root", "Child", "", 4, null)), List.of(
            new NavigationStructure.Entry("items", destination, "list:items", null, 7, NavigationImportance.IMPORTANT)));
    }
}
