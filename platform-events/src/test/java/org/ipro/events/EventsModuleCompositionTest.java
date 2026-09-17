package org.ipro.events;

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
 * D2 (runtime slice): модуль проверяет свой состав сам.
 *
 * <p>Раньше это делал тест приложения ({@code PlatformEventsModuleTest}), а манифест ставил
 * модуль с {@code -DskipTests}: собственных гейтов у артефакта не было вообще, и его можно было
 * опубликовать, не запустив ни одной проверки. Кросс-артефактные свойства (регистрация ровно
 * один раз, отсутствие записи в приложении, происхождение класса из артефакта) остались в
 * приложении: они по природе прикладные, а не модульные.</p>
 */
class EventsModuleCompositionTest {

    private static final Path MODULE = Path.of("").toAbsolutePath();

    /** Reviewed состав runtime-среза: три типа, каждый — исполнение, а не декларация. */
    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.events.EntityEventPublisher",
        "org.ipro.events.config.EventsAutoConfiguration",
        "org.ipro.lifecycle.EntityLifecycleRegistry");

    /** Reviewed compile-поверхность: контракты платформы + API фреймворка. */
    private static final Set<String> REVIEWED_COMPILE_DEPENDENCIES = Set.of(
        "platform-contracts", "spring-context", "spring-tx", "spring-boot-autoconfigure", "slf4j-api");

    /** Пакеты, которых в модуле быть не может: приложение и UI-слои. */
    private static final Set<String> FORBIDDEN_IMPORTS =
        Set.of("org.ip.", "com.vaadin.", "jakarta.", "org.springframework.data.");

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern DEPENDENCY_BLOCK = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern PARENT_BLOCK = Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL);

    @Test
    void moduleCarriesExactlyTheReviewedRuntimeTypes() {
        Set<String> actual = new TreeSet<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            actual.add(packageOf(source) + "." + source.getFileName().toString().replace(".java", ""));
        }

        assertThat(actual)
            .as("runtime-срез — reviewed-бюджет: новая реализация означает, что в модуль попало"
                + " знание write path или UI, то есть направление зависимостей перевернулось")
            .isEqualTo(new TreeSet<>(REVIEWED_TYPES));
    }

    @Test
    void moduleDeclaresOnlyPlatformContractsAndFrameworkApisAtCompileTime() {
        String pom = read(MODULE.resolve("pom.xml"));
        Set<String> declared = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(withoutTestScope(pom));
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        declared.remove("platform-events");
        declared.remove("spring-boot-starter-parent");

        assertThat(declared)
            .as("compile-поверхность модуля зафиксирована ревью: тестовые зависимости не"
                + " считаются — они не попадают в публикуемый артефакт")
            .isEqualTo(new TreeSet<>(REVIEWED_COMPILE_DEPENDENCIES));
        assertThat(pom).doesNotContain("<groupId>org.ip</groupId>");
    }

    @Test
    void runtimeSourcesKnowNeitherTheApplicationNorUiLayers() {
        List<String> foreign = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            Matcher matcher = IMPORT.matcher(read(source));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (FORBIDDEN_IMPORTS.stream().anyMatch(imported::startsWith)) {
                    foreign.add(source.getFileName() + " -> " + imported);
                }
            }
        }

        assertThat(foreign)
            .as("контур событий — нижний слой: он не знает ни приложения, ни UI, ни persistence")
            .isEmpty();
    }

    /** Зависимости вне test-scope: только они попадают в публикуемый артефакт. */
    private static String withoutTestScope(String pom) {
        Matcher dependencies = PARENT_BLOCK.matcher(pom);
        String withoutParent = dependencies.replaceAll(" ");
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
