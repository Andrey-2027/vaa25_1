package org.ipro.query.ui;

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
 * M1.2: UI запроса — артефакт, а не пакет в приложении.
 *
 * <p>Гейт держит ровно то, ради чего модуль выделен: запросный UI не знает ни
 * приложения, ни отчётного DOM, ни формы платформы. Выбор ссылки приходит портом
 * {@code EntitySelection}; попытка вернуть {@code org.ipro.form} напрямую падает
 * здесь, а не в чужой сборке.</p>
 */
class QueryUiBoundaryTest {

    private static final Path SOURCES = Path.of("src/main/java");

    private static final List<String> FORBIDDEN = List.of(
            "import org.ipro.form.",
            "import org.ipro.reportstudio.",
            "import org.ip.",
            "import net.sf.jasperreports.",
            "import org.ipro.report.render.",
            "import org.ipro.report.task.",
            "import ar.com.fdvs.");

    private static final Path IMPORTS = Path.of(
            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");

    @Test
    void queryUiDoesNotDependOnTheApplicationReportsOrThePlatformForm() throws IOException {
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
                .as("UI запроса обязан собираться без приложения, отчётных движков и org.ipro.form")
                .isEmpty();
    }

    @Test
    void moduleRegistersExactlyItsOwnAutoconfiguration() throws IOException {
        assertThat(Files.readAllLines(IMPORTS, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList())
                .containsExactly("org.ipro.query.ui.config.QueryUiAutoConfiguration");
    }

    /**
     * M1.3: тесты UI принадлежат артефакту. Гейт по тестовому дереву не даёт вернуть
     * в них прикладную модель или отчётный DOM: раньше это компилировалось только
     * потому, что тесты лежали в приложении.
     */
    @Test
    void queryUiTestsArePartOfTheArtifactAndDoNotImportTheApplication() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("QueryUiBoundaryTest.java"))
                    .forEach(path -> {
                        String source = read(path);
                        if (source.contains("import org.ip.") || source.contains("import org.ipro.reportstudio.")
                                || source.contains("import org.ipro.form.")) {
                            offenders.add(path.toString().replace('\\', '/'));
                        }
                    });
        }
        assertThat(offenders)
                .as("тесты UI запроса не поднимают приложение, форму платформы и отчётный DOM")
                .isEmpty();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("не удалось прочитать " + path, error);
        }
    }
}
