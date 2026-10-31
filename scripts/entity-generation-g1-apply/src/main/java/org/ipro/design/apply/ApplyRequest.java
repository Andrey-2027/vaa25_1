package org.ipro.design.apply;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Explicit local-file inputs. The source root is relative to the project root and is never
 * inferred from a POM. The state root is fixed under the project, not configurable here.
 *
 * <p>Current dependency locations are required for the extension (v2) profile. The legacy
 * catalog profile keeps the three-argument constructor and does not read them.</p>
 */
public record ApplyRequest(Path projectRoot, String sourceRoot, Path cSummary,
                           List<DependencyLocation> currentDependencies) {
    public ApplyRequest {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(cSummary, "cSummary");
        currentDependencies = List.copyOf(currentDependencies);
    }

    public ApplyRequest(Path projectRoot, String sourceRoot, Path cSummary) {
        this(projectRoot, sourceRoot, cSummary, List.of());
    }
}
