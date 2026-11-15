package org.ipro.navigation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Detached structure of a single menu. */
public record NavigationStructure(long menuId, long version, Instant seededAt, List<Section> sections, List<Entry> entries) {
    public NavigationStructure {
        sections = List.copyOf(sections);
        entries = List.copyOf(entries);
    }

    public record Section(String id, String parentId, String title, String icon, int position, String areaKey) {}

    public record Entry(String id, String sectionId, String functionKey, String title, int position,
                        NavigationImportance importance, boolean stale) {
        public Entry {
            Objects.requireNonNull(importance, "importance");
        }

        public Entry(String id, String sectionId, String functionKey, String title, int position,
                     NavigationImportance importance) {
            this(id, sectionId, functionKey, title, position, importance, false);
        }
    }
}
