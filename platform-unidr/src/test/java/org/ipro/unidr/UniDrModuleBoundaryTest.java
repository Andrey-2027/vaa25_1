package org.ipro.unidr;

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
 * M2.2.1: backend UniDR — самостоятельный артефакт.
 *
 * <p>Прикладной источник, JPQL-адаптер и справочник ссылок остаются у потребителя.
 * Модуль знает только {@code UniDrSourceDefinition}, {@code UniDrReferenceCatalog}
 * и {@code UniDrRunQueryAdapter}; приложение, reportstudio, Vaadin и прикладные
 * сущности здесь запрещены.</p>
 */
class UniDrModuleBoundaryTest {

    private static final Path SOURCES = Path.of("src/main/java");

    private static final List<String> FORBIDDEN = List.of(
            "import org.ip.",
            "import org.ipro.reportstudio.",
            "import com.vaadin.",
            "ReceivingDocument",
            "Nomenclature",
            "UnitOfMeasurement",
            "Workshop");

    @Test
    void backendDoesNotImportTheApplicationReportStudioOrVaadin() throws IOException {
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
            .as("backend UniDR не должен возвращать в артефакт прикладную модель")
            .isEmpty();
    }

    @Test
    void moduleTestsDoNotImportTheApplication() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            files.filter(path -> path.toString().endsWith(".java"))
                .filter(Files::isRegularFile)
                .filter(path -> !path.getFileName().toString().equals("UniDrModuleBoundaryTest.java"))
                .filter(path -> read(path).contains("import org.ip."))
                .forEach(path -> offenders.add(path.toString().replace('\\', '/')));
        }
        assertThat(offenders)
            .as("тесты артефакта используют модульные фикстуры, а не приложение")
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
