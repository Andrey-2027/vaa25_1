package org.ipro.navigation;

import java.util.List;

/** Administrator operations on the persisted menu. */
public interface NavigationStructureService {
    NavigationStructure structure();

    NavigationStructure save(long expectedVersion, NavigationStructure draft);

    List<NavigationFunction> functions();

    List<Unplaced> unplaced();

    RebuildPreview rebuildPreview();

    NavigationStructure rebuild(long expectedVersion);

    record Unplaced(NavigationFunction function, boolean recent) {}

    record RebuildPreview(long version, List<NavigationStructure.Section> removedSections,
                          List<NavigationStructure.Entry> removedEntries, long affectedOverrides) {}
}
