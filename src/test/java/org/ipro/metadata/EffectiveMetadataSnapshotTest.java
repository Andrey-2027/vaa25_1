package org.ipro.metadata;

import org.ip.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4.2: snapshot parity эффективных метаданных (ADR-0007 §6, plan C4.2 п.2, 12, 13).
 *
 * <p>Срез выводит {@code required}/{@code type}/{@code reference} из Bean Validation, JPA и
 * Java-типа вместо единственного явного атрибута. Это осмысленное изменение семантики, а не
 * рефакторинг, поэтому оно проверяется как diff: снимок «до» снят кодом до изменения
 * ({@code effective-metadata-before.txt}), текущий снимок сравнивается с ним, и каждое
 * отличие обязано быть перечислено в {@link #DECLARED_SEMANTIC_DIFF}. Незаявленное отличие
 * ломает тест — именно это делает изменение значений auditable, а не «кажется, должно быть
 * так же».</p>
 *
 * <p>Обновление baseline — осознанное действие: {@code -Dmetadata.snapshot.write=true}
 * перезаписывает целевой файл снимком текущего дерева (файл для ручного переноса в
 * {@code src/test/resources/metadata/}).</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
class EffectiveMetadataSnapshotTest {

    private static final Path WRITE_TARGET =
        Path.of("target", "metadata-snapshot-current.txt");

    /**
     * Перечень изменений effective-фактов, ожидаемых от C4.2. Каждая строка — один факт
     * одного поля: {@code entity#field факт: было -> стало}.
     *
     * <p>Список пуст намеренно, и это измеренный результат, а не отсутствие проверки: перевод
     * пилотов на вывод (удаление дублирующих {@code required}/{@code type}/{@code lookup
     * target}) не изменил ни одного effective-значения — это и доказывает эквивалентность
     * миграции. Изменилось другое: откуда значения берутся (см.
     * {@code effective-metadata-origins.txt}: 21 поле стало обязательным из Bean Validation,
     * 18 ссылок и 24 типа — из JPA). Если любое значение поплывёт, diff перестанет быть пустым
     * и тест упадёт.</p>
     */
    private static final List<String> DECLARED_SEMANTIC_DIFF = List.of();

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private org.ipro.metadata.ManagedEntityCatalog managedEntityCatalog;

    @Test
    void writeCurrentSnapshotWhenRequested() throws IOException {
        if (!Boolean.getBoolean("metadata.snapshot.write")) {
            return;
        }
        Files.createDirectories(WRITE_TARGET.getParent());
        Files.writeString(WRITE_TARGET, currentSnapshot(), StandardCharsets.UTF_8);
        Files.writeString(Path.of("target", "metadata-origins-current.txt"), currentOrigins(),
            StandardCharsets.UTF_8);
        System.out.println("[metadata-snapshot] written to " + WRITE_TARGET);
    }

    /**
     * Origin каждого факта записан, а распределение источников совпадает с зафиксированным.
     * Сводка нужна как измерение: она показывает, что вывод из контракта записи реально
     * участвует, а не остался заявленным правилом.
     */
    @Test
    void everyFactHasARecordedOriginAndTheDistributionMatchesTheBaseline() {
        assertThat(currentOrigins())
            .as("распределение origin'ов effective-фактов")
            .isEqualTo(resource("/metadata/effective-metadata-origins.txt"));
    }

    @Test
    void effectiveFactsMatchTheCheckedInAfterSnapshot() {
        assertThat(currentSnapshot())
            .as("effective-метаданные: снимок совпадает с зафиксированным после C4.2")
            .isEqualTo(resource("/metadata/effective-metadata-after.txt"));
    }

    @Test
    void semanticDiffFromBeforeIsExactlyTheDeclaredList() {
        String before = resource("/metadata/effective-metadata-before.txt");
        List<String> actual = describeDiff(before, currentSnapshot());

        assertThat(actual)
            .as("каждое отличие effective-фактов от снимка «до» обязано быть объявлено")
            .isEqualTo(DECLARED_SEMANTIC_DIFF);
    }

    private String currentSnapshot() {
        return MetadataSnapshotRenderer.render(currentTypes());
    }

    private String currentOrigins() {
        return MetadataSnapshotRenderer.renderOrigins(currentTypes());
    }

    private List<Class<?>> currentTypes() {
        return MetadataSnapshotRenderer.metadataTypes(
            List.copyOf(managedEntityCatalog.managedEntityClasses()));
    }

    private static String resource(String path) {
        try (InputStream stream = EffectiveMetadataSnapshotTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Отсутствует ресурс снимка " + path
                    + " — сгенерируйте его через -Dmetadata.snapshot.write=true");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Построчный diff двух снимков: {@code entity#field факт: было -> стало}. */
    private static List<String> describeDiff(String before, String after) {
        Map<String, String> beforeFacts = facts(before);
        Map<String, String> afterFacts = facts(after);

        List<String> diff = new ArrayList<>();
        for (Map.Entry<String, String> entry : beforeFacts.entrySet()) {
            String current = afterFacts.get(entry.getKey());
            if (current == null) {
                diff.add(entry.getKey() + ": УДАЛЕНО (было " + entry.getValue() + ")");
            } else if (!current.equals(entry.getValue())) {
                diff.add(entry.getKey() + ": " + entry.getValue() + " -> " + current);
            }
        }
        for (String key : afterFacts.keySet()) {
            if (!beforeFacts.containsKey(key)) {
                diff.add(key + ": ДОБАВЛЕНО (" + afterFacts.get(key) + ")");
            }
        }
        return List.copyOf(diff);
    }

    /** Ключ — {@code type#field факт}, значение — само значение факта. */
    private static Map<String, String> facts(String snapshot) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : snapshot.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int space = line.indexOf(' ');
            String key = line.substring(0, space);
            for (String fact : line.substring(space + 1).split(" ")) {
                int eq = fact.indexOf('=');
                result.put(key + " " + fact.substring(0, eq), fact.substring(eq + 1));
            }
        }
        return result;
    }
}
