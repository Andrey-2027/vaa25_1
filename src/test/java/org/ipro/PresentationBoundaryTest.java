package org.ipro;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AF2.5: сервер не использует состав выбора как представление или контракт загрузки. */
class PresentationBoundaryTest {
    @Test void serverConsumersNeverReadSelectionColumns() throws Exception {
        for (String module : List.of("platform-core", "platform-spring-boot-autoconfigure",
                "platform-rls", "platform-rest")) {
            try (var sources = Files.walk(Path.of(module, "src/main/java"))) {
                for (Path file : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                    if (file.getFileName().toString().equals("EntityMetadataInfo.java")) continue;
                    assertThat(Files.readString(file)).as("серверный потребитель %s", file)
                        .doesNotContain("getSelectColumnPaths(");
                }
            }
        }
    }
}
