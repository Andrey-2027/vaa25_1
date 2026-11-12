package org.ipro.vaadin.explorer.ui;

import java.util.Objects;

/**
 * UI-local place, never a published URL or an editable runtime summary.
 * The root and owned context distinguish identically named fields.
 * Future input controls may replace property presenters without changing this navigation.
 */
record CompactCardPlace(Class<?> rootType, Tab tab, DataTarget target) {
    enum Tab { GENERAL, DATA, OWNERS, PRESENTATION, FORMS, LIFECYCLE, NUMBERING }

    sealed interface DataTarget permits RootField, OwnedSection, SectionField { }

    record RootField(String fieldName) implements DataTarget {
        RootField { fieldName = name(fieldName); }
    }

    /** Same owned identity as ExplorerSnapshot: root + owner-link field + row type. */
    record OwnedSection(String ownerField, Class<?> rowType) implements DataTarget {
        OwnedSection {
            ownerField = name(ownerField);
            Objects.requireNonNull(rowType, "rowType");
        }
    }

    record SectionField(OwnedSection section, String fieldName) implements DataTarget {
        SectionField {
            Objects.requireNonNull(section, "section");
            fieldName = name(fieldName);
        }
    }

    CompactCardPlace {
        Objects.requireNonNull(rootType, "rootType");
        Objects.requireNonNull(tab, "tab");
        if (target != null && tab != Tab.DATA) {
            throw new IllegalArgumentException("A data target belongs to the DATA tab");
        }
    }

    static CompactCardPlace general(Class<?> rootType) {
        return new CompactCardPlace(rootType, Tab.GENERAL, null);
    }

    private static String name(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A field name must not be blank");
        }
        return value;
    }
}
