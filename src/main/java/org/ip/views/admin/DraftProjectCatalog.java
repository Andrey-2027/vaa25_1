package org.ip.views.admin;

import org.ipro.design.ProjectDesignContext;
import java.util.Map;

/** Captions are app presentation data; the independent context keeps stable IDs. */
record DraftProjectCatalog(ProjectDesignContext context, Map<String, String> subsystems) {
    DraftProjectCatalog { subsystems = Map.copyOf(subsystems); }
}
