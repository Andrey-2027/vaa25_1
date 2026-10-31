package org.ip.views.reportstudio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R3: маршрутов UDR-редактора больше нет.
 *
 * <p>До R3 канонический {@code ReportEditorView} владел {@code report-editor} и двумя
 * совместимыми URL. Вход снят вместе с классом, поэтому тест проверяет не владельца
 * маршрута, а его отсутствие: старый адрес не должен молча открыть другой экран или
 * остаться забытым alias'ом.</p>
 */
class ReportEditorRouteContractTest {

    private static final Path REPORT_UI = Path.of("platform-report-vaadin/src/main/java/org/ipro/report/ui");
    private static final Path REPORT_STUDIO = REPORT_UI.resolve("catalog");
    private static final Path REPORT_PRINT = REPORT_UI.resolve("print");
    private static final Pattern ROUTE = Pattern.compile("@Route(?:Alias)?\\(\"([^\"]+)\"\\)");
    private static final Pattern PRIMARY_ROUTE = Pattern.compile("@Route\\(\"([^\"]+)\"\\)");

    @Test
    void catalogOwnsItsProductRoutes() {
        String catalog = source(REPORT_STUDIO.resolve("ReportCatalogView.java"));

        assertThat(primaryRoute(catalog)).isEqualTo("report-catalog");
        assertThat(routesIn(REPORT_STUDIO.resolve("ReportCatalogView.java")))
            .contains("report-catalog-compact", "report-catalog-structured");
        assertThat(catalog)
            .contains("@PageTitle(\"Каталог отчётов\")")
            .contains("@PermitAll");
    }

    @Test
    void udrEditorAndItsRoutesAreGone() {
        assertThat(Files.exists(REPORT_STUDIO.resolve("ReportEditorView.java")))
            .as("UDR-редактор удалён в R3 и не возвращается как отдельный экран")
            .isFalse();

        Map<String, String> routes = new LinkedHashMap<>();
        try (Stream<Path> files = Stream.concat(Files.list(REPORT_STUDIO), Files.list(REPORT_PRINT))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String route : routesIn(file)) {
                    assertThat(route)
                        .as("%s объявляет UDR-маршрут", file.getFileName())
                        .doesNotStartWith("report-editor");
                    String previous = routes.put(route, file.getFileName().toString());
                    assertThat(previous)
                        .as("маршрут '%s' объявлен дважды (%s и %s)",
                            route, previous, file.getFileName())
                        .isNull();
                }
            }
        } catch (IOException io) {
            throw new UncheckedIOException(io);
        }
    }

    @Test
    void printDialogNoLongerNavigatesToTheUdrEditor() {
        String dialog = source(REPORT_PRINT.resolve("ReportPrintDialog.java"));

        assertThat(dialog)
            .doesNotContain("ReportEditorView")
            .doesNotContain("UDR — конструктор");
    }

    @Test
    void catalogDoesNotOfferTheUdrConstructor() {
        String catalog = source(REPORT_STUDIO.resolve("ReportCatalogView.java"));

        assertThat(catalog)
            .doesNotContain("UDR — конструктор")
            .doesNotContain("ReportEditorView")
            .doesNotContain("ReportTemplateService");
    }

    private static String primaryRoute(String source) {
        Matcher matcher = PRIMARY_ROUTE.matcher(source);
        assertThat(matcher.find()).as("нет @Route").isTrue();
        return matcher.group(1);
    }

    private static java.util.List<String> routesIn(Path file) {
        Matcher matcher = ROUTE.matcher(source(file));
        java.util.List<String> routes = new java.util.ArrayList<>();
        while (matcher.find()) {
            routes.add(matcher.group(1));
        }
        return routes;
    }

    private static String source(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException io) {
            throw new UncheckedIOException(io);
        }
    }
}
