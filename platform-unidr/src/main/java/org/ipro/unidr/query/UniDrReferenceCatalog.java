package org.ipro.unidr.query;

import org.ipro.metadata.HasDisplayName;

import java.util.List;

/**
 * Application-owned reference lookup used by UniDR definitions and runs.
 *
 * <p>The module never names application entities. The consuming application maps source field
 * IDs to its own entity types and guards every lookup through its canonical service.</p>
 */
public interface UniDrReferenceCatalog {

    /** Display value of a selected/configured reference. */
    record Option(Long id, String caption) implements HasDisplayName {
        @Override public String getDisplayName() { return caption; }
        @Override public String toString() { return java.util.Objects.toString(caption, ""); }
    }

    /** Java type of a reference source field. */
    Class<?> referenceType(String fieldId);

    /** Selectable values for a reference source field. */
    List<Option> search(String fieldId, String term, int limit);

    /** Resolves a configured/default reference ID through the guarded lookup. */
    Option selected(String fieldId, Long id);

    /** Checks that the current user may see the selected reference. */
    void requireVisible(String fieldId, Long id);

    /** Loads a reference by Java entity type name for form initialization. */
    Object entity(String name, Long id);

    /** Resolves an allowed Java entity type by name. */
    Class<?> entityType(String name);

    /** Checks that the current user may use the reference. */
    void requireEntity(String name, Long id);
}
