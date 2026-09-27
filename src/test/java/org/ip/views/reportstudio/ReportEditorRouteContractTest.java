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
 * D3.6.6: канонические экраны владеют основными и совместимыми report routes.
 *
 * <p>D3.6 оставляет один канонический каталог и редактор, которые держат прежние URL
 * через {@code @RouteAlias}. Тест читает исходники:
 * так он проверяет владельца каждого URL и не требует поднимать Vaadin-контекст.
 * Variant-классы удалены; их старые URL ведут прямо на канонические классы.</p>
 *
 * <p>Alias объявлен на том же классе, что и основной {@code @Route}; запросы с alias
 * поэтому проходят через тот же {@code BeforeEnterObserver}, который обрабатывает
 * {@code id} и {@code targetEntityClass}.</p>
 */
class ReportEditorRouteContractTest {

    private static final Path REPORT_STUDIO = Path.of("src/main/java/org/ip/views/reportstudio");
    private static final Path LAUNCHER = REPORT_STUDIO.resolve("ContextualReportLauncher.java");
    /**
     * С E1.6b поток выбора печатной формы живёт здесь: кнопка карточки и объявленное действие
     * списка ведут в один диалог, поэтому проверять владельца URL надо у него, а не у обёртки.
     */
    private static final Path PRINT_DIALOG = REPORT_STUDIO.resolve("ReportPrintDialog.java");
    private static final Pattern ROUTE = Pattern.compile("@Route(?:Alias)?\\(\"([^\"]+)\"\\)");
    private static final Pattern PRIMARY_ROUTE = Pattern.compile("@Route\\(\"([^\"]+)\"\\)");

    /** Канонический editor — тот, который открывает продукт, а не вариант. */
    @Test
    void canonicalEditorOwnsTheProductRoute() {
        String editor = source(REPORT_STUDIO.resolve("ReportEditorView.java"));
        String catalog = source(REPORT_STUDIO.resolve("ReportCatalogView.java"));

        assertThat(primaryRoute(editor)).isEqualTo("report-editor");
        assertThat(primaryRoute(catalog)).isEqualTo("report-catalog");
        assertThat(routesIn(REPORT_STUDIO.resolve("ReportEditorView.java")))
            .contains("report-editor-compact", "report-editor-structured");
        assertThat(routesIn(REPORT_STUDIO.resolve("ReportCatalogView.java")))
            .contains("report-catalog-compact", "report-catalog-structured");

        assertThat(editor)
            .as("точка входа продукта обязана сохранить заголовок и доступ прежними")
            .contains("@PageTitle(\"Редактор отчёта\")")
            .contains("@PermitAll");
        assertThat(editor)
            .as("совместимые URL используют обработчик параметров канонического editor")
            .contains("parameters.getOrDefault(\"id\"")
            .contains("parameters.getOrDefault(\"targetEntityClass\"");
    }

    /**
     * Основные маршруты и aliases обязаны быть уникальными и принадлежать только каноническим
     * экранам.
     */
    @Test
    void routesAreUniqueAcrossReportStudioViews() {
        Map<String, String> owner = new LinkedHashMap<>();
        try (Stream<Path> files = javaFiles(REPORT_STUDIO)) {
            for (Path file : files.toList()) {
                for (String route : routesIn(file)) {
                    String previous = owner.put(route, file.getFileName().toString());
                    assertThat(previous)
                        .as("маршрут '%s' объявлен дважды (%s и %s)",
                            route, previous, file.getFileName())
                        .isNull();
                }
            }
        }

        assertThat(owner)
            .as("в приложении должны остаться два основных route и четыре alias, каждый на "
                + "каноническом экране; пустой или расширенный набор означает изменение контракта")
            .isEqualTo(Map.of(
                "report-editor", "ReportEditorView.java",
                "report-editor-compact", "ReportEditorView.java",
                "report-editor-structured", "ReportEditorView.java",
                "report-catalog", "ReportCatalogView.java",
                "report-catalog-compact", "ReportCatalogView.java",
                "report-catalog-structured", "ReportCatalogView.java"));
    }

    /**
     * Продуктовая точка входа в редактирование — единственная. Этот факт уже был измерен
     * в D3.5.7 и здесь закрепляется как инвариант D3.6: консолидация не имеет права
     * заменить canonical target на вариант.
     */
    @Test
    void contextualLauncherOpensTheCanonicalEditor() {
        String launcher = source(LAUNCHER);
        String printDialog = source(PRINT_DIALOG);

        assertThat(printDialog)
            .as("запуск печати обязан вести в канонический редактор: E1.6b вынесла поток выбора "
                + "печатной формы из кнопки в диалог, и консолидация не имеет права заменить "
                + "canonical target на вариант по дороге")
            .contains("ReportEditorView.class");
        assertThat(launcher)
            .as("кнопка обязана вести в тот же поток, что и печать списка: иначе карточка и "
                + "список разошлись бы в поведении")
            .contains("printDialog()");
        assertThat(launcher + printDialog)
            .as("ни кнопка, ни диалог не имеют права называть variant-редакторы: иначе "
                + "консолидация сохранит вторую точку входа, которую никто не заметит")
            .doesNotContain("ReportEditorViewCompact.class")
            .doesNotContain("ReportEditorViewStructured.class");
    }

    // === helpers ===

    private static String primaryRoute(String source) {
        Matcher matcher = PRIMARY_ROUTE.matcher(source);
        assertThat(matcher.find()).as("канонический экран обязан иметь @Route").isTrue();
        return matcher.group(1);
    }

    private static java.util.List<String> routesIn(Path file) {
        java.util.List<String> routes = new java.util.ArrayList<>();
        Matcher matcher = ROUTE.matcher(source(file));
        while (matcher.find()) {
            routes.add(matcher.group(1));
        }
        return routes;
    }

    private static Stream<Path> javaFiles(Path dir) {
        try {
            return Files.walk(dir).filter(path -> path.toString().endsWith(".java"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String source(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
