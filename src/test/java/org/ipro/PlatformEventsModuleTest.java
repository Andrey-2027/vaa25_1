package org.ipro;

import org.ipro.events.EntityEventPublisher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 (runtime slice): модуль с бинами — это другой класс среза, и его свойства проверяются.
 *
 * <p>`platform-contracts` нёс декларации: их потеря видна компилятору. `platform-events` несёт
 * <b>конфигурацию контейнера</b>, и здесь появляются два новых риска, которых у деклараций не
 * было:</p>
 * <ol>
 * <li>модуль может «забыть представиться» — тогда приложение стартует без контура, а
 *     предметные правила молча не применяются;</li>
 * <li>регистрация авто-конфигураций может разъехаться между артефактами — потерять её можно
 *     без единого признака в коде.</li>
 * </ol>
 *
 * <p>Тест держит три свойства: reviewed состав модуля, reviewed набор зависимостей и
 * самостоятельную регистрацию (плюс отсутствие обязательной записи в приложении). Общий
 * реестр авто-конфигураций по всем артефактам живёт отдельно —
 * {@link PlatformAutoConfigurationRegistryTest}.</p>
 */
class PlatformEventsModuleTest {

    private static final Path MODULE = Path.of("platform-events");
    private static final Path APP_SOURCES = Path.of("src/main/java/org/ipro");

    private static final String IMPORTS_RESOURCE =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** Reviewed состав runtime-среза: три типа, каждый — исполнение, а не декларация. */
    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.events.EntityEventPublisher",
        "org.ipro.events.config.EventsAutoConfiguration",
        "org.ipro.lifecycle.EntityLifecycleRegistry");

    /** Reviewed зависимости: контракты платформы + API фреймворка. */
    private static final Set<String> REVIEWED_DEPENDENCIES = Set.of(
        "platform-contracts", "spring-context", "spring-tx", "spring-boot-autoconfigure", "slf4j-api");

    /** Авто-конфигурации, которые модуль обязан регистрировать сам. */
    private static final Set<String> MODULE_AUTO_CONFIGURATIONS =
        Set.of("org.ipro.events.config.EventsAutoConfiguration");

    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern AUTO_CONFIGURATION = Pattern.compile("@AutoConfiguration\\b");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

    @Test
    void moduleCarriesExactlyTheReviewedRuntimeTypes() {
        Set<String> actual = new TreeSet<>();
        for (Path source : javaSources(MODULE.resolve("src"))) {
            String type = source.getFileName().toString().replace(".java", "");
            actual.add(packageOf(source) + "." + type);
        }

        assertThat(actual)
            .as("runtime-срез — reviewed-бюджет: новая реализация в этом модуле означает, что"
                + " в него попало знание write path или UI, то есть направление зависимостей"
                + " перевернулось")
            .isEqualTo(new TreeSet<>(REVIEWED_TYPES));
    }

    @Test
    void moduleDeclaresOnlyPlatformContractsAndFrameworkApis() {
        String pom = read(MODULE.resolve("pom.xml"));
        Set<String> declared = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(pom);
        while (matcher.find()) {
            String artifactId = matcher.group(1);
            if (!artifactId.equals("platform-events")
                    && !artifactId.equals("spring-boot-starter-parent")) {
                declared.add(artifactId);
            }
        }

        assertThat(declared).isEqualTo(new TreeSet<>(REVIEWED_DEPENDENCIES));
        assertThat(pom).doesNotContain("<groupId>org.ip</groupId>");
        assertThat(pom).doesNotContain("<artifactId>Vaa25_1</artifactId>");
    }

    @Test
    void moduleRegistersItsOwnAutoConfigurationAndTheApplicationDoesNot() {
        Path moduleImports = MODULE.resolve("src/main/resources").resolve(IMPORTS_RESOURCE);
        assertThat(moduleImports)
            .as("без собственного imports-файла модуль не представится контейнеру, и потеря"
                + " контура будет тихой")
            .exists();
        assertThat(lines(moduleImports)).containsExactlyInAnyOrderElementsOf(MODULE_AUTO_CONFIGURATIONS);

        List<String> appImports = lines(Path.of("src/main/resources").resolve(IMPORTS_RESOURCE));
        assertThat(appImports)
            .as("требование roadmap: extraction не увеличивает число обязательных registrations"
                + " в приложении — авто-конфигурация модуля не должна быть перечислена в"
                + " приложении, иначе приложение снова знает о внутренностях модуля")
            .doesNotContainAnyElementsOf(MODULE_AUTO_CONFIGURATIONS);
    }

    @Test
    void runtimeClassesComeFromTheArtifactAndNotFromTheApplicationTree() {
        assertThat(Path.of("src/main/java/org/ipro/events/EntityEventPublisher.java"))
            .as("класс контура обязан исчезнуть из дерева приложения")
            .doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/lifecycle/EntityLifecycleRegistry.java"))
            .doesNotExist();

        URL location = EntityEventPublisher.class.getProtectionDomain()
            .getCodeSource().getLocation();
        assertThat(location.toString())
            .as("владение классами проверяется там, где оно важно — в загруженном артефакте:"
                + " класс контура должен приходить из platform-events, а не из target/classes"
                + " приложения")
            .contains(MODULE.getFileName().toString());
    }

    private static List<String> lines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
