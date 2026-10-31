package org.ipro.report;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1 boundary gate: нейтральный слой {@code org.ipro.report} (данные запроса,
 * экспорт готового JasperPrint, управляемые фоновые задачи) не знает UDR DOM,
 * reportstudio, UniDR и Vaadin. UniDR, в свою очередь, больше не тянет
 * {@code reportstudio.data/render/run}: эти три куска вынесены из reportstudio.
 *
 * <p>С M1 типы датасета живут не в приложении, а в {@code platform-report-core}:
 * их называет query backend, который не может импортировать дерево приложения.
 * Гейт читает оба владельца, поэтому молчаливый возврат копии в приложение
 * тоже падает.</p>
 *
 * <p>R3.2: у UDR DOM не должно остаться потребителей вне {@code reportstudio}.
 * Пока UniDR и query-UI собирали {@code ReportTemplate}, пакет
 * {@code reportstudio.dom} нельзя было удалить; гейт фиксирует, что теперь его
 * называет только собственный движок.</p>
 */
class NeutralReportLayerBoundaryTest {

    private static final List<Path> NEUTRAL_ROOTS = List.of(
            Path.of("platform-report-core/src/main/java/org/ipro/report/data"),
            Path.of("platform-report/src/main/java/org/ipro/report/catalog"),
            Path.of("platform-report/src/main/java/org/ipro/report/render"),
            Path.of("platform-report/src/main/java/org/ipro/report/task"));

    private static final List<String> NEUTRAL_FORBIDDEN = List.of(
            "import org.ipro.reportstudio.",
            "import org.ipro.unidr.",
            "import org.ip.views.",
            "import com.vaadin.");

    private static final Pattern UDR_DOM = Pattern.compile("\\bReportTemplate\\b|\\bReportParam\\b|\\bReportBand\\b");

    private static final List<Path> UNIDR_ROOTS = List.of(
            Path.of("src/main/java/org/ip/unidr"),
            Path.of("platform-report-vaadin/src/main/java/org/ipro/unidr/ui"));

    private static final Path JR_VIEWS = Path.of("src/main/java/org/ip/views/reports");

    private static final List<String> UNIDR_FORBIDDEN = List.of(
            "org.ipro.reportstudio.data.",
            "org.ipro.reportstudio.render.",
            "org.ipro.reportstudio.run.");

    @Test
    void neutralLayerHasNoUdrUniDrOrVaadinImports() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : NEUTRAL_ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .filter(Files::isRegularFile)
                        .forEach(path -> {
                            String source = read(path);
                            for (String forbidden : NEUTRAL_FORBIDDEN) {
                                if (source.contains(forbidden)) {
                                    offenders.add(path.toString().replace('\\', '/') + " -> " + forbidden);
                                }
                            }
                            if (UDR_DOM.matcher(source).find()) {
                                offenders.add(path.toString().replace('\\', '/') + " -> UDR DOM");
                            }
                        });
            }
        }
        assertThat(offenders)
                .as("данные, экспорт и воркер должны быть пригодны без UDR-модели и UI")
                .isEmpty();
    }

    @Test
    void unidrDoesNotImportTheMovedReportStudioChunks() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : UNIDR_ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .filter(Files::isRegularFile)
                        .forEach(path -> {
                            String source = read(path);
                            for (String forbidden : UNIDR_FORBIDDEN) {
                                if (source.contains(forbidden)) {
                                    offenders.add(path.toString().replace('\\', '/') + " -> " + forbidden);
                                }
                            }
                        });
            }
        }
        assertThat(offenders)
                .as("UniDR использует org.ipro.report.{data,render,task}, а не reportstudio "
                        + "датасет/экспорт/воркер")
                .isEmpty();
    }

    @Test
    void jrViewsDoNotGoThroughTheUdrFacade() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(JR_VIEWS)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        if (read(path).contains("org.ipro.reportstudio.run.")) {
                            offenders.add(path.toString().replace('\\', '/'));
                        }
                    });
        }
        assertThat(offenders)
                .as("JR ставит фоновые задачи через org.ipro.report.task, а не через отчётный run-сервис")
                .isEmpty();
    }

    @Test
    void udrDomHasNoConsumersOutsideReportStudio() throws IOException {
        Path reportStudio = Path.of("src/main/java/org/ipro/reportstudio");
        List<String> offenders = new ArrayList<>();
        for (Path root : productionRoots()) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .filter(Files::isRegularFile)
                        .filter(path -> !path.normalize().startsWith(reportStudio))
                        .forEach(path -> {
                            if (read(path).contains("org.ipro.reportstudio.dom")) {
                                offenders.add(path.toString().replace('\\', '/'));
                            }
                        });
            }
        }
        assertThat(offenders)
                .as("UDR DOM принадлежит только reportstudio: его удаление не задевает UniDR, query-UI и платформу")
                .isEmpty();
    }

    /** Production-деревья приложения и платформенных модулей. */
    private static List<Path> productionRoots() throws IOException {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of("src/main/java"));
        try (Stream<Path> modules = Files.list(Path.of("."))) {
            modules.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("platform-"))
                    .map(path -> path.resolve("src/main/java"))
                    .filter(Files::isDirectory)
                    .forEach(roots::add);
        }
        return roots;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("не удалось прочитать " + path, error);
        }
    }
}
