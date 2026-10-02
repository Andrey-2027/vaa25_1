package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendStarterResolvedDependenciesTest {

    private static final Path DEPENDENCY_TREE =
        Path.of("target/backend-starter-runtime-dependency-tree.json");
    private static final Pattern ARTIFACT_ID = Pattern.compile(
        "\"artifactId\"\\s*:\\s*\"([^\"]+)\"");

    @Test
    void resolvedRuntimeClosureDoesNotIncludeTheOptionalRestApi() throws IOException {
        String tree = Files.readString(DEPENDENCY_TREE, StandardCharsets.UTF_8);

        assertTrue(hasArtifact(tree, "platform-backend-starter"), "root artifact missing from Maven tree");
        assertTrue(hasArtifact(tree, "platform-spring-boot-autoconfigure"),
            "backend starter's platform facade missing from resolved Maven tree");
        assertFalse(hasArtifact(tree, "platform-rest"),
            "backend starter must not pull platform-rest through any transitive dependency");
    }

    private static boolean hasArtifact(String tree, String artifactId) {
        return ARTIFACT_ID.matcher(tree).results()
            .anyMatch(match -> artifactId.equals(match.group(1)));
    }
}
