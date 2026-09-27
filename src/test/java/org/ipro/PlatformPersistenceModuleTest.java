package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence ownership boundaries: shared BaseEntity stays in the platform module;
 * JR feature persistence and its registration stay with the application.
 *
 * <p>Module composition is checked in {@code platform-persistence/src/test}; this cross-tree
 * gate verifies ownership and the application-owned JR scan declaration.</p>
 *
 * <p>Динамическая часть о перекрытии деклараций живёт в
 * {@code org.ip.PersistenceRegistrationIT}: scan declarations contribute repositories/entities
 * to the runtime context.</p>
 */
class PlatformPersistenceModuleTest {

    private static final Path MODULE = Path.of("platform-persistence");

    private static final Pattern ANNOTATION = Pattern.compile(
        "^\\s*@(EntityScan|EnableJpaRepositories)\\b", Pattern.MULTILINE);

    @Test
    void theApplicationDeclaresOnlyItsOwnScanPackages() {
        // Приложение сохраняет свои регистрации: @EntityScan для прикладных сущностей
        // и @EnableJpaRepositories для своих репозиториев. Уезжает только чужое.
        assertThat(declaredAnnotations(read(Path.of("src/main/java/org/ip/Application.java"))))
            .containsExactlyInAnyOrder("EntityScan", "EnableJpaRepositories");
        assertThat(read(Path.of("src/main/java/org/ip/Application.java")))
            .doesNotContain("org.ipro.jr.dom");
    }

    @Test
    void thePlatformRepositoryHubDoesNotOwnJrPackages() {
        assertThat(read(Path.of("platform-rls/src/main/java/org/ipro/rls/config/RlsAutoConfiguration.java")))
            .as("JR persistence принадлежит приложению, а не платформенному RLS-хабу")
            .doesNotContain("\"org.ipro.jr\"");
    }

    @Test
    void theApplicationOwnsJrPersistenceRegistration() {
        Path configuration = Path.of(
            "src/main/java/org/ipro/jr/config/JrPersistenceAutoConfiguration.java");
        assertThat(configuration).exists();
        String text = read(configuration);
        assertThat(text).contains("@EntityScan(\"org.ipro.jr.dom\")");
        assertThat(text).contains("@EnableJpaRepositories(\"org.ipro.jr\")");

        assertThat(MODULE.resolve(
            "src/main/java/org/ipro/persistence/config/PersistenceAutoConfiguration.java"))
            .doesNotExist();
        assertThat(MODULE.resolve("src/main/resources/META-INF/spring/"
            + "org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
            .doesNotExist();
    }

    @Test
    void jrTypesAreApplicationOwnedAndBaseEntityStaysInTheModule() {
        assertThat(Path.of("src/main/java/org/ipro/jr/dom/JrxmlTemplate.java")).exists();
        assertThat(Path.of("src/main/java/org/ipro/jr/JrxmlTemplateRepository.java")).exists();
        assertThat(MODULE.resolve("src/main/java/org/ipro/jr/dom/JrxmlTemplate.java"))
            .doesNotExist();
        assertThat(MODULE.resolve("src/main/java/org/ipro/jr/JrxmlTemplateRepository.java"))
            .doesNotExist();
        assertThat(MODULE.resolve(
            "src/main/java/org/ipro/crud/BaseEntity.java")).exists();
        assertThat(Path.of("src/main/java/org/ipro/crud/BaseEntity.java"))
            .as("общая база сущностей остаётся в persistence-капсуле")
            .doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/identity/IdentifiableEntity.java"))
            .as("identifier живёт в Java-only артефакте, а не в дереве приложения")
            .doesNotExist();
    }

    private static List<String> declaredAnnotations(String text) {
        Matcher matcher = ANNOTATION.matcher(text);
        List<String> annotations = new ArrayList<>();
        while (matcher.find()) {
            annotations.add(matcher.group(1));
        }
        return annotations;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
