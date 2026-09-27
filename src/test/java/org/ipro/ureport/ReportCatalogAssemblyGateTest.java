package org.ipro.ureport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Забор вокруг сборки строки каталога (D3.6).
 *
 * <p>До D3.6 одну и ту же запись собирали шесть мест (служба каталога, оба
 * variant-каталога, contextual launcher и анонимные бриджи). Из-за этого
 * «найти» только что созданную копию можно было лишь повторным чтением каталога —
 * и запись из списка могла отличаться от записи, которую открыл редактор.</p>
 *
 * <p>Забор держит это одним утверждением по исходникам: конструктор
 * {@link org.ipro.ureport.catalog.ReportCatalogItem} вызывается в production
 * ровно в одном месте — {@code ReportCatalogItemFactory}. Новый вызов означает,
 * что появилось второе правило сборки, и он должен быть либо переведён на
 * фабрику, либо осознанно внесён сюда.</p>
 */
class ReportCatalogAssemblyGateTest {

    /** Вызов конструктора: единственный способ обойти общий узел сборки. */
    private static final String ASSEMBLY_CALL = "new ReportCatalogItem(";

    /** Единственный production-узел сборки строки каталога. */
    private static final String FACTORY =
            "org/ipro/ureport/catalog/ReportCatalogItemFactory.java";

    @Test
    void catalogRowsAreBuiltOnlyByTheFactory() throws IOException {
        Set<String> builders = builders();
        assertThat(builders)
                .as("строку каталога собирает только %s: второй узел сборки означает, "
                        + "что запись из списка и запись после копии/импорта могут разойтись",
                        FACTORY)
                .containsExactly(FACTORY);
    }

    /** Production-файлы, вызывающие конструктор строки каталога. */
    private static Set<String> builders() throws IOException {
        Path sources = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(sources)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> Files.isRegularFile(path))
                    .filter(ReportCatalogAssemblyGateTest::containsAssemblyCall)
                    .map(path -> sources.relativize(path).toString().replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private static boolean containsAssemblyCall(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(ASSEMBLY_CALL);
        } catch (IOException ex) {
            throw new AssertionError("не удалось прочитать " + path, ex);
        }
    }
}
