package org.ipro.vaadin.explorer.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/** Caller-owned place, section and behavior state of a read-only card. No UI scope or registry filters. */
public final class EntityExplorerCardState {
    private String anchor;
    public EntityExplorerCardState() { }
    public String anchor() { return anchor; }
    void remember(Class<?> type, String anchor) { this.anchor = anchor; }
    private final Map<Class<?>, CompactCardPlace> pilotPlaces = new LinkedHashMap<>();
    private record BehaviorKey(Class<?> root, CompactCardPlace.Tab tab) { }
    private final Map<BehaviorKey, String> behaviorSelections = new LinkedHashMap<>();

    void rememberBehaviorSelection(Class<?> root, CompactCardPlace.Tab tab, String selection) {
        behaviorSelections.put(new BehaviorKey(root, tab), selection);
    }

    String behaviorSelection(Class<?> root, CompactCardPlace.Tab tab) {
        return behaviorSelections.get(new BehaviorKey(root, tab));
    }
    record SectionPlace(Class<?> root, CompactCardPlace.OwnedSection section) { }
    private final Map<SectionPlace, Boolean> pilotSections = new LinkedHashMap<>();
    private final Map<SectionPlace, String> pilotSectionFields = new LinkedHashMap<>();

    boolean sectionOpened(Class<?> root, CompactCardPlace.OwnedSection section) {
        return pilotSections.getOrDefault(new SectionPlace(root, section), false);
    }

    void rememberSectionOpened(Class<?> root, CompactCardPlace.OwnedSection section, boolean opened) {
        pilotSections.put(new SectionPlace(root, section), opened);
    }

    void rememberSectionField(Class<?> root, CompactCardPlace.SectionField field) {
        pilotSectionFields.put(new SectionPlace(root, field.section()), field.fieldName());
    }

    String sectionField(Class<?> root, CompactCardPlace.OwnedSection section) {
        return pilotSectionFields.get(new SectionPlace(root, section));
    }

    /** Local pilot places do not overwrite published legacy anchors. */
    void rememberPilotPlace(CompactCardPlace place) {
        pilotPlaces.put(place.rootType(), place);
    }

    CompactCardPlace pilotPlaceOf(Class<?> type) {
        return pilotPlaces.getOrDefault(type, CompactCardPlace.general(type));
    }

    public void clear() {
        anchor = null;
        pilotPlaces.clear(); behaviorSelections.clear(); pilotSections.clear(); pilotSectionFields.clear();
    }
}