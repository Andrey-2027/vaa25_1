package org.ipro.design;

import java.util.*;

/** Facts supplied by a caller. Incomplete catalogs must never mean 'no conflicts'. */
public record ProjectDesignContext(String projectId, String version, String defaultPackage,
                                   Names existingTypes, Names existingTables, Names subsystemIds, Names iconIds,
                                   Map<String, ReferenceTargetDescriptor> referenceTargets, boolean referencesComplete,
                                   RuntimeDesignSnapshot runtimeSnapshot) {
    /** Source/binary compatibility for the capture-era host; missing runtime facts remain unknown. */
    public ProjectDesignContext(String projectId, String version, String defaultPackage,
            Names existingTypes, Names existingTables, Names subsystemIds, Names iconIds,
            Map<String, ReferenceTargetDescriptor> referenceTargets, boolean referencesComplete) {
        this(projectId,version,defaultPackage,existingTypes,existingTables,subsystemIds,iconIds,
            referenceTargets,referencesComplete,RuntimeDesignSnapshot.unknown());
    }
    public ProjectDesignContext withRuntimeSnapshot(RuntimeDesignSnapshot snapshot) {
        return new ProjectDesignContext(projectId,version,defaultPackage,existingTypes,existingTables,
            subsystemIds,iconIds,referenceTargets,referencesComplete,snapshot);
    }
    public record Names(Set<String> values, boolean complete, String reason) {
        public Names { values = Set.copyOf(values); Objects.requireNonNull(reason); }
        public static Names known(Set<String> values) { return new Names(values, true, ""); }
        public static Names unknown(String reason) { return new Names(Set.of(), false, reason); }
    }
    public ProjectDesignContext {
        Objects.requireNonNull(projectId); Objects.requireNonNull(version); Objects.requireNonNull(existingTypes);
        Objects.requireNonNull(existingTables); Objects.requireNonNull(subsystemIds); Objects.requireNonNull(iconIds);
        Objects.requireNonNull(runtimeSnapshot);
        referenceTargets = Map.copyOf(referenceTargets);
        referenceTargets.forEach((key, value) -> {
            if (!key.equals(value.typeId())) throw new IllegalArgumentException("Reference catalog key mismatch");
        });
    }
}
