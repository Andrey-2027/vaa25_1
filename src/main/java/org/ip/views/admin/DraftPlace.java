package org.ip.views.admin;

import java.util.UUID;
import java.util.Objects;

/** UI navigation is independent of Class-based runtime Explorer places. */
record DraftPlace(UUID draftId, Page page, UUID sectionId, UUID fieldId, String builtinKey) {
    enum Page { GENERAL, DATA }
    DraftPlace {
        Objects.requireNonNull(draftId); Objects.requireNonNull(page);
        if (builtinKey != null && (fieldId != null || sectionId != null)) throw new IllegalArgumentException("One selected target");
    }
    static DraftPlace initial(UUID id) { return new DraftPlace(id, Page.GENERAL, null, null, null); }
    DraftPlace on(Page page) { return new DraftPlace(draftId, page, sectionId, fieldId, builtinKey); }
    DraftPlace field(UUID section, UUID id) { return new DraftPlace(draftId, Page.DATA, section, id, null); }
    DraftPlace section(UUID id) { return new DraftPlace(draftId, Page.DATA, id, null, null); }
    DraftPlace builtin(String key) { return new DraftPlace(draftId, Page.DATA, null, null, key); }
}
