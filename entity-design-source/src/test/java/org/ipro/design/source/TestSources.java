package org.ipro.design.source;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

/** Locations of the real models the tests read; tests are skipped when they are not in this checkout. */
final class TestSources {
    private TestSources() {}

    static final Path CONSUMER = Path.of("..", "examples", "entity-types-consumer", "src", "main", "java").normalize();
    static final Path APPLICATION_MODEL = Path.of("..", "src", "main", "java", "org", "ip", "model").normalize();

    static Path existing(Path path) {
        Assumptions.assumeTrue(Files.isDirectory(path), () -> "not in this checkout: " + path);
        return path;
    }

    static String read(Path root, String relative) {
        try { return Files.readString(root.resolve(relative), java.nio.charset.StandardCharsets.UTF_8); }
        catch (java.io.IOException ex) { throw new AssertionError(ex); }
    }
}
