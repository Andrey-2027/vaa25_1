package org.ipro.report;

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
 * M2.1: общий отчётный слой — артефакт, а не пакет в приложении.
 *
 * <p>Гейт держит границу, ради которой слой выделен: каталог не знает ни одного
 * движка, экспорт не знает UDR/UniDR DOM, а приложение и Vaadin сюда не импортируются.
 * JR-типы в каталоге запрещены отдельно: строка JR собирается в platform-jr.</p>
 */
class ReportModuleBoundaryTest {

    private static final Path SOURCES = Path.of("src/main/java");

    private static final List<String> FORBIDDEN = List.of(
            "import org.ip.",
            "import org.ipro.reportstudio.",
            "import org.ipro.jr.",
            "import com.vaadin.",
            "import ar.com.fdvs.");

    @Test
    void commonReportLayerDoesNotKnowApplicationEnginesOrVaadin() throws IOException {
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
                .as("общий отчётный слой собирается без приложения, JR-типов, reportstudio и Vaadin")
                .isEmpty();
    }

    @Test
    void catalogModuleRegistersOnlyItsOwnAutoConfiguration() throws IOException {
        String imports = read(Path.of(
                "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
        assertThat(imports.strip())
                .isEqualTo("org.ipro.report.config.ReportCatalogAutoConfiguration");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("не удалось прочитать " + path, error);
        }
    }
}
