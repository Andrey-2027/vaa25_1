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
 * D2 (persistence slice), прикладная сторона: приложение и платформенный хаб больше не владеют
 * чужими persistence-пакетами.
 *
 * <p><b>Что здесь осталось.</b> Состав модуля, его compile-зависимости и собственные
 * {@code @EntityScan}/{@code @EnableJpaRepositories} переехали к владельцу —
 * {@code platform-persistence/src/test} (там же {@code @DataJpaTest}-срез, который доказывает
 * регистрацию <b>без</b> приложения). Здесь — только то, что видно с обеих сторон: приложение
 * перечисляет свои пакеты и не перечисляет чужие, модуль себя регистрирует сам, а вынесенные
 * типы исчезли из дерева приложения.</p>
 *
 * <p>Динамическая часть о перекрытии деклараций живёт в
 * {@code org.ip.PersistenceRegistrationIT}: три независимые декларации
 * ({@code org.ip} приложения, хаб, модуль) не перекрывают друг друга — это свойство контекста,
 * а не файла.</p>
 */
class PlatformPersistenceModuleTest {

    private static final Path MODULE = Path.of("platform-persistence");

    private static final String IMPORTS_RESOURCE =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

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
    void thePlatformRepositoryHubDoesNotOwnTheModulePackages() {
        assertThat(read(Path.of("platform-rls/src/main/java/org/ipro/rls/config/RlsAutoConfiguration.java")))
            .as("модуль RLS не владеет чужими пакетами: свои регистрирует"
                + " RlsPersistenceAutoConfiguration, а пакеты persistence-модуля — сам модуль")
            .doesNotContain("\"org.ipro.jr\"");
    }

    @Test
    void modulePresentsItselfToTheContainer() {
        assertThat(lines(MODULE.resolve("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .as("регистрация модуля обязана быть его собственной: потеря imports-файла делает"
                + " артефакт невидимым для контейнера")
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

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
