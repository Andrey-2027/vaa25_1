package org.ipro.design.existing;

import java.util.List;
import java.util.Objects;
import org.ipro.design.FieldApplicationMapping.CompositionMode;

/** Source declaration intent and resolved order are separate, including when EXPLICIT equals AUTO. */
public record ExistingFormColumns(CompositionMode mode, List<String> declaredPaths, List<String> resolvedPaths,
                                  String sourceTypeId, String sourcePath, String sourceSha256, boolean resolutionComplete) {
    public ExistingFormColumns(CompositionMode mode, List<String> declaredPaths, List<String> resolvedPaths,
                               String sourceTypeId, String sourcePath, String sourceSha256) {
        this(mode, declaredPaths, resolvedPaths, sourceTypeId, sourcePath, sourceSha256, mode != CompositionMode.UNKNOWN);
    }
    public ExistingFormColumns {
        Objects.requireNonNull(mode);
        if (!resolutionComplete && !resolvedPaths.isEmpty()) throw new IllegalArgumentException("Incomplete resolution cannot publish guessed paths");
        declaredPaths = List.copyOf(declaredPaths); resolvedPaths = List.copyOf(resolvedPaths);
        Objects.requireNonNull(sourceTypeId); Objects.requireNonNull(sourcePath); Objects.requireNonNull(sourceSha256);
    }
    public static ExistingFormColumns unknown() {
        return new ExistingFormColumns(CompositionMode.UNKNOWN, List.of(), List.of(), "", "", "");
    }
}
