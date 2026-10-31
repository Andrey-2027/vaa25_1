package org.ipro.reportstudio.query;

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
 * Границы, которые остались в дереве приложения после M1/M1.2: backend запроса уехал в
 * platform-query, UI запроса — в platform-query-vaadin (там его границы проверяет
 * {@code QueryUiBoundaryTest} модуля), а здесь живёт только run-слой отчётов.
 */
class ReportQueryUiBoundaryTest {

    /**
     * M1.2/R3.2: пакеты редактора/конструктора/списка/каталога и адаптер над формой не
     * возвращаются в приложение. Проверка по файлам, а не по импортам: копия рано или
     * поздно нашла бы потребителя, и граница поехала бы молча.
     */
    @Test
    void queryUiPackagesNoLongerLiveInTheApplication() throws IOException {
        assertThat(javaSources(Path.of("src/main/java/org/ipro/reportstudio/query")))
                .as("query backend/UI живёт в platform-query/platform-query-vaadin; адаптер"
                        + " над формой удалён вместе с UDR")
                .isEmpty();
    }

    /**
     * M1.3: тесты запросного backend/UI уехали к владельцу вместе с кодом. Дерево
     * {@code src/test/java/org/ipro/query} в приложении — копия, которая снова начала
     * бы компилироваться против прикладных фикстур и маскировала зависимость модуля.
     */
    @Test
    void queryModuleTestsNoLongerLiveInTheApplication() {
        assertThat(Path.of("src/test/java/org/ipro/query"))
                .as("тесты запроса живут в platform-query/platform-query-vaadin; в приложении "
                        + "остаются только прикладные acceptance-сценарии")
                .doesNotExist();
    }

    /**
     * R3.2: run-слой — нейтральная инфраструктура (кэш, воркер, экспорт). UDR-фасад
     * запуска удалён вместе с движком, поэтому отчётного DOM здесь не остаётся вообще.
     */
    @Test
    void runPackageKeepsNoReportDom() throws IOException {
        Path root = Path.of("src/main/java/org/ipro/reportstudio/run");
        List<String> withDom = new ArrayList<>();
        for (Path path : javaSources(root)) {
            String source = read(path);
            boolean dom = source.contains("import org.ipro.reportstudio.dom.")
                    || java.util.regex.Pattern.compile("\\bReportTemplate\\b|\\bReportParam\\b|\\bReportBand\\b")
                            .matcher(source).find();
            if (dom) {
                withDom.add(path.toString().replace('\\', '/'));
            }
        }
        assertThat(withDom)
                .as("run-слой не знает отчётного DOM: UDR-фасад удалён")
                .isEmpty();
    }

    private static List<Path> javaSources(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .toList();
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
