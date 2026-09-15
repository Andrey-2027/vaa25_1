package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * D2 (persistence slice): модуль с persistence-типами отвечает на риск §3.5 карты D1.
 *
 * <p>Риск был: {@code @EntityScan} и {@code @EnableJpaRepositories} задаются
 * централизованно, поэтому вынесенный тип может остаться без регистрации. Ответ среза —
 * модуль объявляет свои пакеты сам, а приложение о них больше не знает. Тест держит три
 * свойства:</p>
 * <ol>
 * <li>reviewed состав и зависимости модуля;</li>
 * <li>модуль сам несёт обе декларации, а приложение и платформенный хаб — уже нет;</li>
 * <li>типы уехали из дерева приложения: копия в дереве означала бы, что граница"
 *     декоративная.</li>
 * </ol>
 *
 * <p>Динамическая часть — в {@code PersistenceRegistrationIT} (репозиторий существует как бин,
 * entity в persistence unit, ни одна чужая декларация не перекрыта) и в
 * {@link PersistenceTypeRegistrationTest} (реестр покрытия типов).</p>
 */
class PlatformPersistenceModuleTest {

    private static final Path MODULE = Path.of("platform-persistence");

    private static final String IMPORTS_RESOURCE =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.crud.BaseEntity",
        "org.ipro.jr.JrxmlTemplateRepository",
        "org.ipro.jr.dom.JrxmlTemplate",
        "org.ipro.persistence.config.PersistenceAutoConfiguration");

    private static final Set<String> REVIEWED_DEPENDENCIES = Set.of(
        "crudui-core",
        "jakarta.persistence-api",
        "jakarta.validation-api",
        "spring-data-jpa",
        "spring-boot-autoconfigure",
        "spring-boot-persistence",
        "hibernate-core");

    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern ANNOTATION = Pattern.compile(
        "^\\s*@(EntityScan|EnableJpaRepositories)\\b", Pattern.MULTILINE);

    @Test
    void moduleCarriesExactlyTheReviewedPersistenceTypes() {
        Set<String> actual = new TreeSet<>();
        for (Path source : javaSources(MODULE.resolve("src"))) {
            actual.add(packageOf(source) + "." + source.getFileName().toString().replace(".java", ""));
        }

        assertThat(actual)
            .as("persistence-капсула — reviewed: сюда попадают только типы, которые нужны"
                + " артефакту, чтобы быть самодостаточным (включая базу сущностей), но не"
                + " реализации платформы")
            .isEqualTo(new TreeSet<>(REVIEWED_TYPES));
    }

    @Test
    void moduleDeclaresOnlyPersistenceApis() {
        String pom = read(MODULE.resolve("pom.xml"));
        Set<String> declared = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(pom);
        while (matcher.find()) {
            String artifactId = matcher.group(1);
            if (!artifactId.equals("platform-persistence")
                    && !artifactId.equals("spring-boot-starter-parent")) {
                declared.add(artifactId);
            }
        }

        assertThat(declared).isEqualTo(new TreeSet<>(REVIEWED_DEPENDENCIES));
        assertThat(pom).doesNotContain("<groupId>org.ip</groupId>");
        assertThat(pom).doesNotContain("<artifactId>Vaa25_1</artifactId>");
    }

    @Test
    void moduleDeclaresItsOwnScanPackagesAndTheApplicationDoesNot() {
        String autoConfiguration = read(MODULE
            .resolve("src/main/java/org/ipro/persistence/config/PersistenceAutoConfiguration.java"));
        assertThat(declaredAnnotations(autoConfiguration))
            .as("модуль обязан объявлять и persistence unit, и Spring Data: иначе его типы"
                + " остаются в артефакте без регистрации")
            .containsExactlyInAnyOrder("EntityScan", "EnableJpaRepositories");
        assertThat(autoConfiguration).contains("\"org.ipro.jr.dom\"");
        assertThat(autoConfiguration).contains("\"org.ipro.jr\"");

        // Приложение сохраняет свои три регистрации: @EntityScan для прикладных сущностей
        // и @EnableJpaRepositories для своих репозиториев. Уезжает только чужое.
        assertThat(declaredAnnotations(read(Path.of("src/main/java/org/ip/Application.java"))))
            .containsExactlyInAnyOrder("EntityScan", "EnableJpaRepositories");
        assertThat(read(Path.of("src/main/java/org/ip/Application.java")))
            .doesNotContain("org.ipro.jr.dom");

        assertThat(read(Path.of("src/main/java/org/ipro/rls/config/RlsAutoConfiguration.java")))
            .as("платформенный хаб репозиториев больше не перечисляет пакеты модуля — их"
                + " объявляет сам модуль, и две декларации не перекрываются")
            .doesNotContain("\"org.ipro.jr\"");

        assertThat(lines(MODULE.resolve("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .containsExactly("org.ipro.persistence.config.PersistenceAutoConfiguration");
    }

    @Test
    void persistenceTypesLeftTheApplicationTree() {
        assertThat(Path.of("src/main/java/org/ipro/jr/dom/JrxmlTemplate.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/jr/JrxmlTemplateRepository.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/crud/BaseEntity.java"))
            .as("база сущностей входит в persistence-капсулу: без неё entity не компилируется"
                + " вне дерева приложения")
            .doesNotExist();
    }

    private static List<String> declaredAnnotations(String text) {
        Matcher matcher = ANNOTATION.matcher(text);
        List<String> annotations = new java.util.ArrayList<>();
        while (matcher.find()) {
            annotations.add(matcher.group(1));
        }
        return annotations;
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
