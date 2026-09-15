package org.ipro.search;

import org.ip.config.DataInitializer;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.data.SearchFieldResolver;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
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
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4.8 (план п.7): per-entity search matrix как <b>проверяемый артефакт</b>, а не абзац в
 * статусе.
 *
 * <p>Для каждого {@code STANDARD_ROOT} фиксируется вывод единой лестницы полей поиска
 * ({@code SearchFieldResolver}: явные поля → {@code @SearchFields} → {@code @InstanceName} →
 * строковые {@code selectColumns}) и участие в глобальном поиске. Snapshot снимается один
 * раз и коммитится как human-reviewed baseline; любое изменение полей поиска ломает тест,
 * поэтому дифф поисковой поверхности становится видимым, а не молчаливым.</p>
 *
 * <p>Поведенческая часть матрицы (blank term, case, literal escaping, ranking, order,
 * paging) закреплена отдельно: {@code CharacterizationStandardPathIT}, {@code SearchTermsTest}
 * и {@code CanonicalWritePathIT.blankAndSpecialCharacterSearchStayBounded}.</p>
 *
 * <p>Baseline обновляется осознанно: {@code -Dsearch.snapshot.write=true} пишет текущий
 * снимок в {@code target/search-fields-current.txt} для ручного переноса в ресурс.</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
class SearchFieldParitySnapshotTest {

    private static final Path WRITE_TARGET = Path.of("target", "search-fields-current.txt");

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private ManagedEntityCatalog managedEntityCatalog;

    @Autowired
    private EntityDescriptorCatalog entityDescriptorCatalog;

    @Autowired
    private MetadataResolver metadataResolver;

    @Autowired
    private InstanceNameResolver instanceNameResolver;

    @Test
    void writeCurrentSnapshotWhenRequested() throws IOException {
        if (!Boolean.getBoolean("search.snapshot.write")) {
            return;
        }
        Files.createDirectories(WRITE_TARGET.getParent());
        Files.writeString(WRITE_TARGET, currentSnapshot(), StandardCharsets.UTF_8);
        System.out.println("[search-snapshot] written to " + WRITE_TARGET);
    }

    @Test
    void searchFieldsMatchTheCheckedInParitySnapshot() {
        assertThat(currentSnapshot())
            .as("поисковая поверхность standard roots изменилась: обновите baseline осознанно")
            .isEqualTo(resource("/search/search-fields-after.txt"));
    }

    /** Детерминированный снимок: тип, участие в global search, итоговые поля поиска. */
    private String currentSnapshot() {
        SearchFieldResolver resolver =
            new SearchFieldResolver(metadataResolver, instanceNameResolver);
        List<String> lines = new ArrayList<>();
        managedEntityCatalog.managedEntityClasses().stream()
            .sorted(Comparator.comparing(Class::getName))
            .filter(type -> entityDescriptorCatalog.descriptorOf(type).exposure()
                == EntityExposure.STANDARD_ROOT)
            .forEach(type -> lines.add(type.getName()
                + " global=" + type.isAnnotationPresent(GlobalSearchable.class)
                + " fields=" + String.join(",", resolver.resolve(type, List.of()))));
        // "\n" явно, а не lineSeparator(): иначе baseline ломается при переходе
        // Windows/Linux CI — тот же переносимый контракт, что у metadata-снимка.
        return String.join("\n", lines) + "\n";
    }

    private static String resource(String path) {
        try (InputStream stream =
                 SearchFieldParitySnapshotTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Отсутствует ресурс снимка " + path
                    + " — сгенерируйте его через -Dsearch.snapshot.write=true");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
