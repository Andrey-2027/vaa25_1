package org.ipro.query;

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
 * M1 boundary gate: query backend не знает отчётный DOM, UDR-модель, UniDR-адаптеры,
 * приложение и Vaadin. С Q1/Q2 гейт жил в дереве приложения; после физического
 * переноса в platform-query он проверяет сам артефакт, поэтому потеря зависимости
 * видна сборке модуля, а не только приложению.
 */
class QueryBackendBoundaryTest {

    private static final Path MODULE_SOURCES = Path.of("src/main/java");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "import org.ipro.reportstudio.",
            "import org.ip.unidr.",
            "import org.ip.",
            "import com.vaadin.");

    @Test
    void moduleDoesNotImportReportstudioApplicationOrVaadin() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MODULE_SOURCES)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .forEach(path -> collectOffenders(path, offenders));
        }
        assertThat(offenders)
                .as("query backend должен говорить на нейтральном языке: reportstudio, "
                        + "приложение и Vaadin живут в адаптерах/UI, а не в артефакте запроса")
                .isEmpty();
    }

    @Test
    void moduleDoesNotCallReportExecutionService() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MODULE_SOURCES)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .filter(path -> read(path).contains("ReportExecutionService"))
                    .forEach(path -> offenders.add(path.toString().replace('\\', '/')));
        }
        assertThat(offenders)
                .as("подготовка запроса не должна зависеть от отчётного run-сервиса; "
                        + "группы и сортировки приходят обычными QuerySort")
                .isEmpty();
    }

    /** Срез регистра отдаётся как QueryDefinition; шаблон собирает только UDR-адаптер. */
    @Test
    void registerSliceRecipesDoNotBuildReportTemplates() throws IOException {
        Path root = Path.of("src/main/java/org/ipro/query/register");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        String source = read(path);
                        if (java.util.regex.Pattern.compile("\\bReportTemplate\\b").matcher(source).find()
                                || java.util.regex.Pattern.compile("\\bReportParam\\b").matcher(source).find()) {
                            offenders.add(path.toString().replace('\\', '/'));
                        }
                    });
        }
        assertThat(offenders)
                .as("register slice должен отдавать QueryDefinition, а ReportTemplate/ReportParam — "
                        + "только в переходном UDR-адаптере")
                .isEmpty();
    }

    /**
     * R3: результат запроса подписывается через порт, а не через сервисы
     * приложения/регистра, которые backend не должен знать.
     */
    @Test
    void queryExecutorDependsOnThePresentationPortOnly() throws IOException {
        String source = read(Path.of("src/main/java/org/ipro/query/ReportQueryExecutor.java"));
        assertThat(source)
                .as("executor принимает QueryResultPresenter и не ссылается на прикладные сервисы")
                .doesNotContain("org.ipro.data.presentation")
                .doesNotContain("org.ipro.register.RecorderTypeRegistry")
                .doesNotContain("usePresentations")
                .contains("QueryResultPresenter");
    }

    /** Артефакт не объявляет бины отчётного UI: только query-механизм. */
    @Test
    void moduleRegistryContainsOnlyTheQueryAutoConfiguration() throws IOException {
        String imports = read(Path.of(
                "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
        assertThat(imports.strip())
                .isEqualTo("org.ipro.query.config.QueryAutoConfiguration");
    }

    /**
     * M1.3: тесты — часть артефакта, а не приложения. Пока они лежали в дереве
     * приложения, модуль мог незаметно снова начать зависеть от {@code org.ip.model}:
     * сборка модуля такой тест просто не компилировала. Гейт проверяет только импорты,
     * поэтому javadoc-упоминания прикладных типов в фикстурах ему не мешают.
     */
    @Test
    void moduleTestsDoNotImportTheApplicationOrTheReportDom() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("QueryBackendBoundaryTest.java"))
                    .forEach(path -> {
                        String source = read(path);
                        if (source.contains("import org.ip.") || source.contains("import org.ipro.reportstudio.")) {
                            offenders.add(path.toString().replace('\\', '/'));
                        }
                    });
        }
        assertThat(offenders)
                .as("тесты артефакта не поднимают приложение и отчётный DOM; прикладные "
                        + "сценарии остаются в дереве потребителя")
                .isEmpty();
    }

    private static void collectOffenders(Path path, List<String> offenders) {
        String source = read(path);
        for (String forbidden : FORBIDDEN_IMPORTS) {
            if (source.contains(forbidden)) {
                offenders.add(path.toString().replace('\\', '/') + " -> " + forbidden);
            }
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("не удалось прочитать " + path, error);
        }
    }
}
