package org.ipro.navigation;

import java.time.Instant;
import java.util.List;

/** Detached snapshot of the persisted menu. Keys are stored without profile prefixes. */
public record NavigationStructure(long version, Instant seededAt, List<Section> sections, List<Entry> entries) {
    public NavigationStructure {
        sections = List.copyOf(sections);
        entries = List.copyOf(entries);
    }

    public record Section(String id, String parentId, String title, String icon, int position, String areaKey) {}
    public record Entry(String id, String sectionId, String functionKey, String title, int position, boolean stale) {
        public Entry(String id, String sectionId, String functionKey, String title, int position) {
            this(id, sectionId, functionKey, title, position, false);
        }
    }
}
