package org.ipro.persistence;

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
 * D2 (persistence slice): модуль проверяет свой узкий общий persistence-контракт.
 *
 * <p>Раньше это делал тест приложения, а манифест ставил модуль с {@code -DskipTests}. Разбор
 * D1/D2 назвал разрыв: артефакт можно было опубликовать, не запустив ни одной проверки. Здесь —
 * то, что артефакт видит сам: reviewed-состав и reviewed compile-зависимости. Feature-specific
 * entity/repository registration belongs to its feature owner, not this shared module.</p>
 */
class PersistenceModuleCompositionTest {

    private static final Path MODULE = Path.of("").toAbsolutePath();

    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.crud.BaseEntity");

    /** Reviewed compile-поверхность: нейтральный identifier + API JPA/валидации/Spring Data/Hibernate. */
    private static final Set<String> REVIEWED_COMPILE_DEPENDENCIES = Set.of(
        "platform-identity-api",
        "jakarta.persistence-api",
        "jakarta.validation-api",
        "spring-data-jpa",
        "hibernate-core");

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern DEPENDENCY_BLOCK = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern PARENT_BLOCK = Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL);
    @Test
    void moduleCarriesExactlyTheReviewedPersistenceTypes() {
        Set<String> actual = new TreeSet<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            actual.add(packageOf(source) + "." + source.getFileName().toString().replace(".java", ""));
        }

        assertThat(actual)
            .as("persistence-капсула — reviewed: сюда попадают только типы, нужные артефакту,"
                + " чтобы быть самодостаточным (включая базу сущностей), но не реализации платформы")
            .isEqualTo(new TreeSet<>(REVIEWED_TYPES));
    }

    @Test
    void moduleDeclaresOnlyPersistenceApisAndTheNeutralIdentityContract() {
        String pom = read(MODULE.resolve("pom.xml"));
        Set<String> declared = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(withoutTestScope(pom));
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        declared.remove("platform-persistence");
        declared.remove("spring-boot-starter-parent");

        assertThat(declared)
            .as("compile-поверхность модуля зафиксирована ревью: UI-артефакта (crudui-core) здесь"
                + " быть не может — иначе persistence-модуль платформы зависит от Vaadin")
            .isEqualTo(new TreeSet<>(REVIEWED_COMPILE_DEPENDENCIES));
        assertThat(declared).doesNotContain("crudui-core");
    }

    @Test
    void moduleDoesNotOwnFeatureSpecificPersistenceRegistration() {
        assertThat(MODULE.resolve(
            "src/main/java/org/ipro/persistence/config/PersistenceAutoConfiguration.java"))
            .doesNotExist();
        assertThat(MODULE.resolve("src/main/resources/META-INF/spring/"
            + "org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
            .doesNotExist();
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
