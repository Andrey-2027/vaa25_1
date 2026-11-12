package org.ipro.vaadin.explorer.ui;

import java.util.Optional;
import java.util.List;

/** A known card anchor. It carries neither a route nor a Workspace entry identity. */
public record EntityExplorerCardPlace(String anchor) {
    public EntityExplorerCardPlace {
        if (ExplorerCardAnchor.of(anchor).isEmpty()) throw new IllegalArgumentException("Unknown card anchor");
    }
    public static Optional<EntityExplorerCardPlace> parse(String anchor) {
        return ExplorerCardAnchor.of(anchor).map(value -> new EntityExplorerCardPlace(value.value()));
    }
    public static List<String> knownAnchors() {
        var anchors = new java.util.ArrayList<String>();
        for (var tab : ExplorerCardTab.values()) {
            anchors.add(tab.id());
            ExplorerCardSection.ALL.stream().filter(section -> section.tab() == tab)
                .map(section -> tab.id() + "/" + section.id()).forEach(anchors::add);
        }
        return List.copyOf(anchors);
    }
}
