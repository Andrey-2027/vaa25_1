package org.ipro.design.apply;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One current dependency location supplied by the consumer/integration adapter. The identifier is
 * the Maven coordinate used by the C summary; the path comes from the current build configuration,
 * never from C, a receipt or untrusted JSON.
 */
public record DependencyLocation(String id, Path path) {
    public DependencyLocation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(path, "path");
    }
}
