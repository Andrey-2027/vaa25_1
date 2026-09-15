package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2: слой контрактов — отдельный артефакт, и это проверяется, а не подразумевается.
 *
 * <p>D1 зафиксировал границу картой и правилами направлений. D2 сделал её физической:
 * `platform-contracts` — самостоятельный Maven-проект, который приложение получает как
 * зависимость. Такая граница держится сборкой (у модуля нет класса приложения на пути
 * компиляции), но её можно потерять незаметно: достаточно вернуть тип в дерево приложения
 * «для удобства» или добавить в контракты зависимость на платформенную реализацию.</p>
 *
 * <p>Тест держит пять свойств слоя контрактов:</p>
 * <ol>
 * <li>состав среза — reviewed-бюджет: 34 типа в 5 пакетах, ни больше ни меньше;</li>
 * <li>внешние импорты — только разрешённый список (внешний API-артефакт и нейтральный
 *     identifier, опубликованный в другом платформенном артефакте);</li>
 * <li>объявленные зависимости помника совпадают с reviewed-списком;</li>
 * <li>у каждого типа контракта ровно один дом — в дереве приложения копии нет;</li>
 * <li>пакеты, которые теперь разделены между артефактами (split package), перечислены
 *     явно: расширение этого списка — осознанное решение, а не побочный эффект.</li>
 * </ol>
 */
class PlatformContractsModuleTest {

    private static final Path MODULE = Path.of("platform-contracts");
    private static final Path MODULE_SOURCES = MODULE.resolve("src");

    /** Прикладное дерево исходников: тот же FQN здесь означал бы вторую копию контракта. */
    private static final Path APPLICATION_SOURCES = Path.of("src/main/java");

    /**
     * Reviewed-бюджет среза: пакет -> точный набор типов. Пакеты контрактов сохранены,
     * поэтому переносы не потребовали правок в коде; измениться этот список может только
     * вместе с решением о расширении среза.
     */
    private static final Map<String, Set<String>> REVIEWED_SLICE = reviewedSlice();

    /**
     * Разрешённые внешние импорты контрактов: только эти пакеты не-JDK. Обоснование —
     * в {@code platform-contracts/pom.xml}. Появление любого другого пакета означает, что
     * в контракты попало знание платформенной реализации.
     */
    private static final Set<String> ALLOWED_EXTERNAL_IMPORTS = Set.of(
        "org.slf4j",      // внешний API-артефакт: MDC для correlation id события
        "org.ipro.crud"   // нейтральный identifier, уже опубликованный в crudui-core
    );

    /** Reviewed-набор зависимостей помника модуля. */
    private static final Set<String> REVIEWED_DEPENDENCIES = Set.of("slf4j-api", "crudui-core");

    /**
     * Внутренние связи среза: контракты ссылаются друг на друга (контекст lifecycle — на
     * контекст события). Это по-прежнему один артефакт, а не выход за его границу, поэтому
     * такие импорты разрешены — но только они, и только внутри reviewed-набора.
     */
    private static final Set<String> SLICE_TYPES = sliceTypeNames();

    /**
     * Пакеты, существующие и в модуле контрактов, и в дереве платформы: срез идёт по типам,
     * поэтому часть пакетов оказывается разделена между артефактами. Это зафиксированное
     * решение D2 (единица разреза — тип), а не незамеченное разрастание.
     *
     * <p>Runtime-срез D2 сократил список: `org.ipro.events` и `org.ipro.lifecycle` ушли в
     * `platform-events` целиком, поэтому разделённых пакетов осталось два.</p>
     */
    private static final Set<String> REVIEWED_SPLIT_PACKAGES = Set.of(
        "org.ipro.data",
        "org.ipro.fetch.plan"
    );

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");

    @Test
    void contractsModuleCarriesExactlyTheReviewedSlice() {
        Map<String, Set<String>> actual = new TreeMap<>();
        for (Path source : javaSources(MODULE_SOURCES)) {
            String type = source.getFileName().toString().replace(".java", "");
            actual.computeIfAbsent(packageOf(source), key -> new TreeSet<>()).add(type);
        }

        assertThat(actual)
            .as("состав среза контрактов — это reviewed-бюджет: тип, попавший сюда"
                + " случайно, расширяет публичную поверхность платформы, а пропавший —"
                + " незаметно возвращает контракт в дерево приложения")
            .isEqualTo(new TreeMap<>(REVIEWED_SLICE));
    }

    @Test
    void contractsDependOnNothingButTheJdkAndTheReviewedExternalApis() {
        List<String> foreignImports = new ArrayList<>();
        for (Path source : javaSources(MODULE_SOURCES)) {
            Matcher matcher = IMPORT.matcher(read(source));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (imported.startsWith("java.")) {
                    continue;
                }
                boolean allowed = ALLOWED_EXTERNAL_IMPORTS.stream()
                    .anyMatch(prefix -> imported.startsWith(prefix + "."))
                    || SLICE_TYPES.contains(imported);
                if (!allowed) {
                    foreignImports.add(source.getFileName() + " -> " + imported);
                }
            }
        }

        assertThat(foreignImports)
            .as("слой контрактов собирается без платформенных зависимостей: появление"
                + " jakarta/spring/vaadin импорта означает, что в контракты попало"
                + " платформенное знание, и это отдельное решение (политика D1 §6.3)")
            .isEmpty();
    }

    @Test
    void contractsModuleDeclaresOnlyTheReviewedDependencies() {
        String pom = read(MODULE.resolve("pom.xml"));
        Set<String> declared = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(pom);
        while (matcher.find()) {
            String artifactId = matcher.group(1);
            if (!artifactId.equals("platform-contracts") && !artifactId.equals("spring-boot-starter-parent")) {
                declared.add(artifactId);
            }
        }

        assertThat(declared)
            .as("набор зависимостей модуля контрактов зафиксирован ревью: новая зависимость"
                + " здесь — это либо платформенная реализация внутри контрактов, либо"
                + " изменение политики, которое должно быть названо явно")
            .isEqualTo(new TreeSet<>(REVIEWED_DEPENDENCIES));
        assertThat(pom).doesNotContain("<groupId>org.ip</groupId>");
        assertThat(pom).doesNotContain("<artifactId>Vaa25_1</artifactId>");
    }

    @Test
    void everyContractTypeLivesOnlyInTheContractsModule() {
        List<String> duplicates = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : REVIEWED_SLICE.entrySet()) {
            Path appPackage = APPLICATION_SOURCES.resolve(entry.getKey().replace('.', '/'));
            for (String type : entry.getValue()) {
                if (Files.exists(appPackage.resolve(type + ".java"))) {
                    duplicates.add(entry.getKey() + "." + type);
                }
            }
        }

        assertThat(duplicates)
            .as("у контракта обязан быть ровно один дом: копия в дереве приложения делает"
                + " границу модуля декоративной, потому что компилятор возьмёт локальный класс")
            .isEmpty();
    }

    @Test
    void splitPackagesAreExplicitAndReviewed() {
        Set<String> split = new TreeSet<>();
        for (String contractPackage : REVIEWED_SLICE.keySet()) {
            Path appPackage = APPLICATION_SOURCES.resolve(contractPackage.replace('.', '/'));
            if (Files.exists(appPackage) && !javaSources(appPackage).isEmpty()) {
                split.add(contractPackage);
            }
        }

        assertThat(split)
            .as("рез идёт по типам, поэтому часть пакетов разделена между артефактами."
                + " Список таких пакетов reviewed: расширять его — значит сознательно"
                + " увеличивать число split package в платформе; уменьшать — задача срезов")
            .isEqualTo(new TreeSet<>(REVIEWED_SPLIT_PACKAGES));
    }

    private static String packageOf(Path source) {
        String text = read(source);
        Matcher matcher = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE).matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("нет package: " + source);
        }
        return matcher.group(1);
    }

    private static Set<String> sliceTypeNames() {
        Set<String> types = new TreeSet<>();
        REVIEWED_SLICE.forEach((contractPackage, names) -> names.forEach(name -> types.add(contractPackage + "." + name)));
        return types;
    }

    private static Map<String, Set<String>> reviewedSlice() {
        Map<String, Set<String>> slice = new LinkedHashMap<>();
        slice.put("org.ipro.metadata.annotation", Set.of(
            "EntityKind", "EntityMetadata", "FieldMetadata", "FieldType", "GridColumn", "Lookup",
            "RequiredMode", "SectionPersistenceMode", "SectionRlsPolicy", "Subsystem",
            "TableSectionMetadata", "TableSections"));
        slice.put("org.ipro.events", Set.of(
            "AggregateSavingEvent", "AggregateSection", "EntityChangedEvent", "EntityDeletedEvent",
            "EntityDeletingEvent", "EntityEvent", "EntitySavedEvent", "EntitySavingEvent",
            "EventContext", "EventSource"));
        slice.put("org.ipro.lifecycle", Set.of(
            "AggregateSaveContext", "EntityChangedContext", "EntityDeleteContext", "EntityLifecycle",
            "EntitySaveContext", "EntityUpdateContext"));
        slice.put("org.ipro.data", Set.of(
            "DataOperation", "EntityCapabilityOverride", "EntityExposure", "EntityExposureOverride",
            "SearchFields"));
        slice.put("org.ipro.fetch.plan", Set.of("FetchScenario"));
        return slice;
    }

    private static List<Path> javaSources(Path root) {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Проверка «источники найдены»: пустой срез сделал бы все проверки выше вакуумными. */
    @Test
    void sliceIsNotEmpty() {
        assertThat(javaSources(MODULE_SOURCES)).hasSize(34);
        Set<Path> seen = new LinkedHashSet<>(javaSources(MODULE_SOURCES));
        assertThat(seen).hasSize(34);
    }
}
