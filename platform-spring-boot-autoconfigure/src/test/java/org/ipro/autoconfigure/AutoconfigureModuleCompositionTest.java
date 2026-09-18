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

    /** Запрещённые пакеты: приложение ({@code org.ip}) и Vaadin ({@code com.vaadin}). */
    private static final Pattern FORBIDDEN_PACKAGE = Pattern.compile("\\b(?:org\\.ip|com\\.vaadin)\\.");

    /** Одиночная строка import: {@code import [static] <target>; }. */
    private static final Pattern IMPORT_STATEMENT = Pattern.compile(
        "^\\s*import\\s+(?:static\\s+)?(.+?)\\s*;");

    /** Wildcard-импорт запрещённого пакета: скрывает набор типов, поэтому не проверяем. */
    private static final Pattern WILDCARD_IMPORT = Pattern.compile(
        "^\\s*import\\s+(?:static\\s+)?(?:org\\.ip|com\\.vaadin)\\.[\\w.]*\\*\\s*;");

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

    /**
     * Модуль wiring не знает ни приложения, ни Vaadin — это границы platform-vaadin и org.ip.
     *
     * <p><b>Ссылка распознаётся в трёх синтаксисах, а не только в строках {@code import}.</b>
     * Это вывод D1: первые версии таких заборов читали только {@code import}, и ссылка,
     * записанная fully-qualified, обходила правило молча — то есть запрет существовал для тех,
     * кто пишет импорты единообразно. Wildcard-импорт запрещён отдельно: он скрывает набор
     * типов, поэтому его нельзя ни перечислить, ни проверить.</p>
     */
    @Test
    void moduleSourcesDoNotReferenceApplicationOrVaadin() {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            violations.addAll(forbiddenReferences(
                source.getFileName().toString(), read(source)));
        }
        assertThat(violations).isEmpty();
    }

    /**
     * Самозамер забора. Гейт, который никогда не находил нарушения, ничего не доказывает:
     * D1 показал, что «правило есть» и «правило работает» — разные утверждения. Здесь
     * проверяются все три распознаваемых синтаксиса и отрицательные случаи (комментарий,
     * собственный пакет платформы {@code org.ipro}, который подстрокой похож на {@code org.ip}).
     */
    @Test
    void forbiddenReferenceScannerSeesAllThreeSyntaxes() {
        assertThat(forbiddenReferences("A.java",
            "import org.ip.model.Nomenclature;")).hasSize(1);
        assertThat(forbiddenReferences("A.java",
            "import static com.vaadin.flow.shared.ApplicationConstants.APP_PATH;")).hasSize(1);
        assertThat(forbiddenReferences("A.java",
            "import com.vaadin.flow.shared.*;")).hasSize(1);
        assertThat(forbiddenReferences("A.java",
            "class A implements com.vaadin.flow.component.Component {}")).hasSize(1);
        assertThat(forbiddenReferences("A.java",
            "class A { void m() { org.ip.model.Nomenclature n = null; } }")).hasSize(1);

        // Комментарии не являются связкой, а собственный пакет платформы — не запрещён.
        assertThat(forbiddenReferences("A.java",
            "// import org.ip.model.Nomenclature;\n"
                + "/** com.vaadin.flow.component.Component */\nclass A {}")).isEmpty();
        assertThat(forbiddenReferences("A.java",
            "import org.ipro.metadata.MetadataResolver;\n"
                + "import org.ipro.form.spi.WorkspaceGateway;")).isEmpty();
    }

    /**
     * Все три синтаксиса ссылки на запрещённый пакет в одном месте. Комментарии вырезаются
     * (javadoc вправе обсуждать границу, называя её запрещённые пакеты), строковые литералы
     * сохраняются — ссылка на класс по имени тоже связка. Реализация stripper'а повторяет
     * {@code CoreModuleCompositionTest} в platform-core: одна методика на все заборы проекта,
     * чтобы «ссылка найдена» означало одно и то же.
     */
    private static List<String> forbiddenReferences(String fileName, String source) {
        List<String> violations = new ArrayList<>();
        for (String line : withoutComments(source).split("\\R")) {
            Matcher importMatcher = IMPORT_STATEMENT.matcher(line);
            if (importMatcher.find()) {
                String target = importMatcher.group(1).trim();
                if (WILDCARD_IMPORT.matcher(line).find()) {
                    violations.add(fileName
                        + " → wildcard-импорт " + target + " (скрывает набор типов)");
                } else if (FORBIDDEN_PACKAGE.matcher(target).find()) {
                    violations.add(fileName + " → импорт " + target);
                }
                continue;
            }
            Matcher reference = FORBIDDEN_PACKAGE.matcher(line);
            if (reference.find()) {
                violations.add(fileName + " → fully-qualified ссылка " + line.trim());
            }
        }
        return violations;
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

    /**
     * Вырезает комментарии, сохраняя содержимое строковых литералов. Простая регулярка здесь
     * не годится: символы открытия блочного комментария внутри строкового литерала начали бы
     * «комментарий» и съели остаток файла, а признак строчного комментария внутри строки съел бы
     * остаток строки — вместе с возможной настоящей ссылкой. Реализация повторяет
     * {@code CoreModuleCompositionTest} в platform-core: одна методика на все заборы проекта,
     * чтобы «ссылка найдена» означало одно и то же.
     */
    private static String withoutComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '"') {
                boolean textBlock = source.startsWith("\"\"\"", index);
                String delimiter = textBlock ? "\"\"\"" : "\"";
                result.append(delimiter);
                index += delimiter.length();
                while (index < source.length()) {
                    if (source.startsWith(delimiter, index) && !isEscaped(source, index)) {
                        result.append(delimiter);
                        index += delimiter.length();
                        break;
                    }
                    result.append(source.charAt(index));
                    index++;
                }
                continue;
            }
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                int closing = source.indexOf("*/", index + 2);
                index = closing < 0 ? source.length() : closing + 2;
                continue;
            }
            result.append(current);
            index++;
        }
        return result.toString();
    }

    private static boolean isEscaped(String source, int index) {
        int backslashes = 0;
        for (int probe = index - 1; probe >= 0 && source.charAt(probe) == '\\'; probe--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
