package org.ipro.jr;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2.1: движок JR — артефакт add-on, а не пакет приложения.
 *
 * <p>Приложение и его UI сюда не импортируются; JPQL-источник приходит портом
 * {@link org.ipro.jr.run.JpqlDatasetRunner}, реализация которого живёт у потребителя.
 * Каталог JR-строку собирает здесь, а не в нейтральной фабрике.</p>
 */
class JrModuleBoundaryTest {

    private static final Path SOURCES = Path.of("src/main/java");

    private static final List<String> FORBIDDEN = List.of(
            "import org.ip.",
            "import org.ipro.reportstudio.",
            "import com.vaadin.");

    @Test
    void jrEngineDoesNotImportTheApplicationReportStudioOrVaadin() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        String source = read(path);
                        for (String forbidden : FORBIDDEN) {
                            if (source.contains(forbidden)) {
                                offenders.add(path.toString().replace('\\', '/') + " -> " + forbidden);
                            }
                        }
                    });
        }
        assertThat(offenders)
                .as("JR-движок зависит от контрактов платформы и порта JPQL, а не от приложения")
                .isEmpty();
    }

    @Test
    void moduleRegistersPersistenceAndServiceConfigurations() throws IOException {
        String imports = read(Path.of(
                "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
        assertThat(imports.strip().lines().map(String::trim).filter(line -> !line.isEmpty()).toList())
                .containsExactly(
                        "org.ipro.jr.config.JrPersistenceAutoConfiguration",
                        "org.ipro.jr.config.JrAutoConfiguration");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("не удалось прочитать " + path, error);
        }
    }
}
