package org.ipro.report.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** M2.2.2 gate: the report UI module does not know the application or its reportstudio. */
class ReportUiBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final List<String> FORBIDDEN = List.of(
            "import org.ip.",
            "org.ipro.reportstudio.",
            "UniDrReceivingItemsSource",
            "UniDrReferenceService",
            "@ComponentScan");

    @Test
    void productionSourcesHaveNoApplicationOrReportStudioReferences() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
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
                .as("UI отчётов и UniDR живёт на портах, а не на org.ip/reportstudio")
                .isEmpty();
    }

    @Test
    void beansAreRegisteredByAutoConfigurationNotByScanning() throws IOException {
        Path imports = Path.of("src/main/resources/META-INF/spring/"
                + "org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(read(imports))
                .contains("org.ipro.report.ui.config.ReportUiAutoConfiguration");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("не удалось прочитать " + path, error);
        }
    }
}
