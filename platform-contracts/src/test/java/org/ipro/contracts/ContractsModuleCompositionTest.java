package org.ipro.contracts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 (слой контрактов): модуль проверяет свой состав и свою чистоту сам.
 *
 * <p>Раньше это делал тест приложения, а манифест ставил модуль с {@code -DskipTests} — то есть
 * артефакт можно было опубликовать, не запустив ни одной его проверки. Разбор D1/D2 назвал это
 * разрывом владения: гейт был у потребителя, а не у владельца. Кросс-артефактные свойства
 * (нет копии типа в дереве приложения, явный список разделённых пакетов) остались в приложении:
 * они требуют видеть обе стороны сразу.</p>
 *
 * <p>Здесь проверяется то, что видит сам модуль: reviewed-состав среза, reviewed-набор
 * compile-зависимостей и отсутствие в исходниках чего-либо, кроме JDK, slf4j и нейтрального
 * identifier'а.</p>
 */
class ContractsModuleCompositionTest {

    private static final Path MODULE = Path.of("").toAbsolutePath();

    /**
     * Reviewed-бюджет среза. Пакеты контрактов сохранены, поэтому переносы не потребовали правок
     * в коде; измениться этот список может только вместе с решением о расширении среза
     * (политика D1 §6.3).
     */
    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.metadata.annotation.EntityKind",
        "org.ipro.metadata.annotation.EntityMetadata",
        "org.ipro.metadata.annotation.FieldMetadata",
        "org.ipro.metadata.annotation.FieldType",
        "org.ipro.metadata.annotation.GridColumn",
        "org.ipro.metadata.annotation.Lookup",
        "org.ipro.metadata.annotation.RequiredMode",
        "org.ipro.metadata.annotation.SectionPersistenceMode",
        "org.ipro.metadata.annotation.SectionRlsPolicy",
        "org.ipro.metadata.annotation.Subsystem",
        "org.ipro.metadata.annotation.TableSectionMetadata",
        "org.ipro.metadata.annotation.TableSections",
        "org.ipro.events.AggregateSavingEvent",
        "org.ipro.events.AggregateSection",
        "org.ipro.events.EntityChangedEvent",
        "org.ipro.events.EntityDeletedEvent",
        "org.ipro.events.EntityDeletingEvent",
        "org.ipro.events.EntityEvent",
        "org.ipro.events.EntitySavedEvent",
        "org.ipro.events.EntitySavingEvent",
        "org.ipro.events.EventContext",
        "org.ipro.events.EventSource",
        "org.ipro.lifecycle.AggregateSaveContext",
        "org.ipro.lifecycle.EntityChangedContext",
        "org.ipro.lifecycle.EntityDeleteContext",
        "org.ipro.lifecycle.EntityLifecycle",
        "org.ipro.lifecycle.EntitySaveContext",
        "org.ipro.lifecycle.EntityUpdateContext",
        "org.ipro.data.DataOperation",
        "org.ipro.data.EntityCapabilityOverride",
        "org.ipro.data.EntityExposure",
        "org.ipro.data.EntityExposureOverride",
        "org.ipro.data.SearchFields",
        "org.ipro.fetch.plan.FetchScenario");

    /**
     * Разрешённые package-префиксы внешних импортов: внешний API-артефакт и нейтральный
     * identifier. Любой другой не-JDK импорт означает, что в контракты попало знание
     * платформенной реализации.
     */
    private static final Set<String> ALLOWED_EXTERNAL_IMPORTS =
        Set.of("org.slf4j", "org.ipro.identity");

    /** Reviewed compile-поверхность модуля: тестовые зависимости в неё не входят. */
    private static final Set<String> REVIEWED_COMPILE_DEPENDENCIES =
        Set.of("slf4j-api", "platform-identity-api");

    /**
     * Прикладной пакет как отдельное имя: без границ «org.ip» матчилось бы внутри
     * «org.ipro.*» — платформенных имён, которые здесь как раз законны.
     */
    private static final Pattern APPLICATION_PACKAGE =
        Pattern.compile("(?<![\\w.])org\\.ip(?![\\w])");

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern DEPENDENCY_BLOCK = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern PARENT_BLOCK = Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL);

    @Test
    void moduleCarriesExactlyTheReviewedSlice() {
        Set<String> actual = new TreeSet<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            actual.add(packageOf(source) + "." + source.getFileName().toString().replace(".java", ""));
        }

        assertThat(actual)
            .as("состав среза контрактов — reviewed-бюджет: тип, попавший сюда случайно,"
                + " расширяет публичную поверхность платформы, а пропавший — незаметно"
                + " возвращает контракт в дерево приложения")
            .isEqualTo(new TreeSet<>(REVIEWED_TYPES));
        assertThat(actual).hasSize(34);
    }

    @Test
    void moduleImportsNothingButTheJdkAndTheReviewedExternalApis() {
        List<String> foreignImports = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            Matcher matcher = IMPORT.matcher(read(source));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (imported.startsWith("java.")) {
                    continue;
                }
                boolean allowed = ALLOWED_EXTERNAL_IMPORTS.stream()
                    .anyMatch(prefix -> imported.startsWith(prefix + "."))
                    || REVIEWED_TYPES.contains(imported);
                if (!allowed) {
                    foreignImports.add(source.getFileName() + " -> " + imported);
                }
            }
        }

        assertThat(foreignImports)
            .as("слой контрактов собирается без платформенных зависимостей: jakarta/spring/vaadin"
                + " импорт означает, что в контракты попало платформенное знание, и это отдельное"
                + " решение (политика D1 §6.3)")
            .isEmpty();
    }

    @Test
    void moduleDeclaresOnlyTheReviewedCompileDependencies() {
        String pom = read(MODULE.resolve("pom.xml"));
        Set<String> declared = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(withoutTestScope(pom));
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        declared.remove("platform-contracts");
        declared.remove("spring-boot-starter-parent");

        assertThat(declared)
            .as("набор compile-зависимостей модуля зафиксирован ревью: новая зависимость — это"
                + " либо платформенная реализация внутри контрактов, либо изменение политики,"
                + " которое должно быть названо явно")
            .isEqualTo(new TreeSet<>(REVIEWED_COMPILE_DEPENDENCIES));
    }

    @Test
    void moduleDoesNotKnowTheApplicationPackage() {
        List<String> references = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/main"))) {
            if (APPLICATION_PACKAGE.matcher(read(source)).find()) {
                references.add(source.getFileName().toString());
            }
        }

        assertThat(references)
            .as("org.ip не может появляться в контрактах ни кодом, ни строкой — это единственное"
                + " жёсткое правило D1 (его же проверяет PlatformStringDependencyTest)")
            .isEmpty();
    }

    /** Зависимости вне test-scope: только они попадают в публикуемый артефакт. */
    private static String withoutTestScope(String pom) {
        String withoutParent = PARENT_BLOCK.matcher(pom).replaceAll(" ");
        Matcher blocks = DEPENDENCY_BLOCK.matcher(withoutParent);
        StringBuilder result = new StringBuilder(pom.length());
        while (blocks.find()) {
            if (!blocks.group().contains("<scope>test</scope>")) {
                result.append(blocks.group()).append('\n');
            }
        }
        return result.toString();
    }

    private static String packageOf(Path source) {
        Matcher matcher = PACKAGE.matcher(read(source));
        if (!matcher.find()) {
            throw new IllegalStateException("нет package: " + source);
        }
        return matcher.group(1);
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
}
