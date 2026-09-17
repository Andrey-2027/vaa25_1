package org.ipro.autoconfigure;

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
 * D3.4: модуль wiring проверяет свой состав сам — по прецеденту
 * {@code CoreModuleCompositionTest} в platform-core.
 *
 * <p>Модуль содержит только авто-конфигурации и property bean, поэтому его reviewed-реестр
 * короткий и намеренно рукописный: лишний класс здесь расширяет поверхность wiring, которого
 * не должно быть, а пропавший незаметно возвращает конфигурацию в дерево приложения.</p>
 */
class AutoconfigureModuleCompositionTest {

    private static final Path MODULE = Path.of("").toAbsolutePath();

    private static final Pattern PACKAGE = Pattern.compile("package\\s+([\\w.]+)\\s*;");

    private static final Pattern ARTIFACT_ID = Pattern.compile(
        "<artifactId>([^<]+)</artifactId>");

    /** Reviewed-реестр состава: все production-типы модуля на сегодня. */
    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.autoconfigure.PlatformProperties",
        "org.ipro.crud.config.CrudAutoConfiguration",
        "org.ipro.metadata.config.MetadataAutoConfiguration",
        "org.ipro.fetch.config.FetchPlanInstanceNameAutoConfiguration",
        "org.ipro.data.config.DataAccessAutoConfiguration",
        "org.ipro.search.config.GlobalSearchAutoConfiguration");

    /**
     * Reviewed-реестр imports: модуль регистрирует ровно пять backend-конфигураций.
     * Порядок в файле не задаёт порядок контекста — его определяют @AutoConfigureAfter.
     */
    private static final Set<String> REVIEWED_IMPORTS = Set.of(
        "org.ipro.metadata.config.MetadataAutoConfiguration",
        "org.ipro.crud.config.CrudAutoConfiguration",
        "org.ipro.fetch.config.FetchPlanInstanceNameAutoConfiguration",
        "org.ipro.data.config.DataAccessAutoConfiguration",
        "org.ipro.search.config.GlobalSearchAutoConfiguration");

    /** Reviewed compile/test-зависимости pom — каждая строка закрывает конкретные импорты. */
    private static final Set<String> REVIEWED_DEPENDENCIES = Set.of(
        "platform-core", "platform-contracts", "platform-events",
        "platform-numbering", "platform-rls",
        "spring-context", "spring-boot", "spring-boot-autoconfigure",
        "jakarta.persistence-api", "jakarta.validation-api",
        "spring-boot-starter-test");

    @Test
    void moduleContainsExactlyTheReviewedTypes() {
        assertThat(actualTypes()).isEqualTo(REVIEWED_TYPES);
    }

    @Test
    void importsFileListsExactlyTheReviewedConfigurations() {
        assertThat(readImports()).containsExactlyInAnyOrderElementsOf(REVIEWED_IMPORTS);
    }

    @Test
    void pomDeclaresExactlyTheReviewedDependencies() {
        assertThat(dependencies()).isEqualTo(REVIEWED_DEPENDENCIES);
    }

    /** Модуль wiring не знает ни приложения, ни Vaadin — это границы platform-vaadin и org.ip. */
    @Test
    void moduleSourcesDoNotReferenceApplicationOrVaadin() {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            for (String line : read(source).split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import org.ip.")
                    || trimmed.startsWith("import com.vaadin.")) {
                    violations.add(source.getFileName() + " → " + trimmed);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    private static Set<String> actualTypes() {
        Set<String> types = new TreeSet<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            Matcher matcher = PACKAGE.matcher(read(source));
            if (!matcher.find()) {
                throw new IllegalStateException("нет package: " + source);
            }
            String name = source.getFileName().toString();
            types.add(matcher.group(1) + "."
                + name.substring(0, name.length() - ".java".length()));
        }
        return types;
    }

    private static List<String> readImports() {
        Path imports = MODULE.resolve("src/main/resources/META-INF/spring")
            .resolve("org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        try {
            return Files.readAllLines(imports, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> dependencies() {
        Matcher matcher = ARTIFACT_ID.matcher(read(MODULE.resolve("pom.xml")));
        Set<String> ids = new TreeSet<>();
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        // родитель-стартер и собственный артефакт модуля зависимостями не являются
        ids.remove("spring-boot-starter-parent");
        ids.remove("platform-spring-boot-autoconfigure");
        return ids;
    }

    private static List<Path> javaSources(Path root) {
        if (!Files.isDirectory(root)) {
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
