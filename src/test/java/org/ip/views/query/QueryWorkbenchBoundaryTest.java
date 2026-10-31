package org.ip.views.query;

import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
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
 * T1: самостоятельный экран запроса не тянет отчётную подсистему.
 *
 * <p>Сценарий «query → таблица» обязан работать без каталога отчётов, без запуска
 * шаблонов и без UDR/JR-движков. Компилятор этого не доказывает: пакет
 * {@code org.ip.views.query} собирается в том же модуле, где лежат движки, поэтому
 * запрет выражен явно — по импортам, а не по ClassNotFound в проде.</p>
 */
class QueryWorkbenchBoundaryTest {

    private static final Path QUERY_VIEWS = Path.of("src/main/java/org/ip/views/query");

    /** Пакеты отчётных движков и старого UDR: экран их не знает. */
    private static final List<String> FORBIDDEN = List.of(
            "org.ipro.reportstudio.render.",
            "org.ipro.reportstudio.run.",
            "org.ipro.reportstudio.catalog.",
            "org.ipro.reportstudio.dom.",
            "org.ipro.reportstudio.service.",
            "org.ipro.report.render.",
            "org.ipro.report.task.",
            "org.ipro.report.catalog.",
            "net.sf.jasperreports.",
            "ar.com.fdvs.",
            "org.ipro.unidr.");

    @Test
    void screenIsTheStandaloneQueryRouteWithItsOwnEntryRule() {
        Route route = QueryWorkbenchView.class.getAnnotation(Route.class);

        assertThat(route).as("экран обязан иметь собственный адрес, а не прятаться за редактором отчёта")
                .isNotNull();
        assertThat(route.value()).isEqualTo("queries");
        assertThat(QueryWorkbenchView.class.getAnnotation(PageTitle.class).value())
                .isEqualTo("Конструктор запросов");
        assertThat(QueryWorkbenchView.class.isAnnotationPresent(PermitAll.class))
                .as("решение о входе принимает QueryWorkbenchAccess, а не аннотация маршрута")
                .isTrue();
    }

    @Test
    void queryViewsDoNotDependOnReportEngines() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(QUERY_VIEWS)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        String source = read(path);
                        for (String forbidden : FORBIDDEN) {
                            if (source.contains(forbidden)) {
                                offenders.add(QUERY_VIEWS.relativize(path).toString().replace('\\', '/')
                                        + " -> " + forbidden);
                            }
                        }
                    });
        }

        assertThat(offenders)
                .as("экран запроса не должен открывать каталог отчётов или запускать отчётные движки:"
                        + " T1 доказывает границу подсистемы, а не переиспользует её UI")
                .isEmpty();
    }

    @Test
    void screenDoesNotSaveTheQueryIntoAReportTemplate() throws IOException {
        for (Path path : List.of(QUERY_VIEWS.resolve("QueryWorkbenchView.java"),
                QUERY_VIEWS.resolve("QueryWorkbenchAccess.java"))) {
            String source = read(path);
            assertThat(source)
                    .as("%s: срез T1 ничего не сохраняет, поэтому шаблон отчёта здесь не появляется", path)
                    .doesNotContain("ReportTemplate");
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
