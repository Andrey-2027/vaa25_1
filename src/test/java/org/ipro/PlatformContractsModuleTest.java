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
 * <p>Тест держит три свойства слоя контрактов:</p>
 * <ol>
 * <li>он не тянет ничего, кроме JDK, — иначе это уже не контракт, а реализация;</li>
 * <li>тип контракта существует ровно в одном месте — перенос не должен оставить копию;</li>
 * <li>модуль не зависит от приложения и не упоминает его артефакт.</li>
 * </ol>
 */
class PlatformContractsModuleTest {

    private static final Path MODULE = Path.of("platform-contracts");
    private static final Path MODULE_SOURCES = MODULE.resolve("src");

    /** Перенесённый срез: пакет аннотаций metadata, сохранённый без изменений. */
    private static final String CONTRACTS_PACKAGE = "org.ipro.metadata.annotation";

    /** Прикладное дерево исходников: тот же пакет здесь означал бы вторую копию контрактов. */
    private static final Path APPLICATION_SOURCES = Path.of("src/main/java/org/ipro");

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+);", Pattern.MULTILINE);

    @Test
    void contractsModuleExistsAndCarriesTheExtractedTypes() {
        assertThat(MODULE.resolve("pom.xml")).exists();
        assertThat(MODULE.resolve("src/main/java/org/ipro/metadata/annotation")).isDirectory();
        assertThat(javaSources(MODULE_SOURCES)).hasSize(12);
    }

    @Test
    void contractsDependOnNothingButTheJdk() {
        List<String> foreignImports = new ArrayList<>();
        for (Path source : javaSources(MODULE_SOURCES)) {
            Matcher matcher = IMPORT.matcher(read(source));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (!imported.startsWith("java.")) {
                    foreignImports.add(MODULE.getFileName() + ": " + source.getFileName() + " -> " + imported);
                }
            }
        }

        assertThat(foreignImports)
            .as("слой контрактов должен собираться без внешних зависимостей: появление"
                + " jakarta/spring/vaadin импорта означает, что в контракты попало"
                + " платформенное знание, и это отдельное решение (политика D1 §6.3)")
            .isEmpty();
    }

    @Test
    void contractsModuleDoesNotDependOnTheApplication() {
        String pom = read(MODULE.resolve("pom.xml"));

        // Проверяются именно координаты зависимости, а не любое упоминание: в описании
        // модуля прикладной пакет назван как запрет («не знает org.ip»), и это не связка.
        assertThat(pom).doesNotContain("<dependencies>");
        assertThat(pom).doesNotContain("<groupId>org.ip</groupId>");
        assertThat(pom).doesNotContain("<artifactId>Vaa25_1</artifactId>");
    }

    @Test
    void extractedPackageLivesOnlyInTheContractsModule() {
        Path moved = APPLICATION_SOURCES.resolve(CONTRACTS_PACKAGE.replace('.', '/'));
        assertThat(moved)
            .as("перенесённый пакет обязан исчезнуть из дерева приложения: иначе контракт"
                + " существует в двух местах и граница модуля ничего не значит")
            .doesNotExist();
    }

    private static List<Path> javaSources(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
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
