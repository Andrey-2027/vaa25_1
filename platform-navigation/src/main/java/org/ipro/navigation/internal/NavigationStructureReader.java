package org.ipro.navigation.internal;

import org.ipro.navigation.NavigationStructure;
import org.ipro.navigation.store.NavigationEntryRepository;
import org.ipro.navigation.store.NavigationMenuEntity;
import org.ipro.navigation.store.NavigationSectionRepository;

/** Projects one menu within the caller's transaction. */
public final class NavigationStructureReader {
    private final NavigationSectionRepository sections;
    private final NavigationEntryRepository entries;

    public NavigationStructureReader(NavigationSectionRepository sections, NavigationEntryRepository entries) {
        this.sections = sections;
        this.entries = entries;
    }

    public NavigationStructure read(NavigationMenuEntity menu) {
        long id = menu.getId();
        return new NavigationStructure(id, menu.getVersion(), menu.getSeededAt(),
            sections.findByMenu_Id(id).stream().map(row -> new NavigationStructure.Section(row.getKey(),
                row.getParent() == null ? null : row.getParent().getKey(), row.getTitle(), row.getIcon(),
                row.getPosition(), row.getAreaKey())).toList(),
            entries.findByMenu_Id(id).stream().map(row -> new NavigationStructure.Entry(row.getKey(),
                row.getSection().getKey(), row.getFunctionKey(), row.getTitle(), row.getPosition(),
                row.getImportance())).toList());
    }
}
