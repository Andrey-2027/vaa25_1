package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Реестр авто-конфигураций по <b>всем</b> артефактам сразу (D2).
 *
 * <p>После того как платформа начала выезжать в отдельные модули, регистрация стала
 * распределённой: приложение перечисляет свои авто-конфигурации, каждый модуль — свои. У
 * такой схемы две тихие ошибки:</p>
 *
 * <ol>
 * <li>авто-конфигурация вообще не зарегистрирована — подсистема молча выключается,
 *     компилятор молчит, тесты, которые её не касаются, зелёные;</li>
 * <li>зарегистрирована дважды или чужим артефактом — порядок применения становится
 *     неопределённым, а «кто владелец» перестаёт быть проверяемым.</li>
 * </ol>
 *
 * <p>Плюс третья, самая обидная: запись осталась, а класс уехал в другой артефакт. Spring
 * упал бы на старте с {@code ClassNotFoundException} — но лучше узнать об этом из теста,
 * который читает объявления. Поэтому проверяется и обратное направление: каждая запись
 * обязана указывать на класс, который действительно лежит в этом артефакте.</p>
 */
class PlatformAutoConfigurationRegistryTest {

    private static final String IMPORTS_RESOURCE =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private static final Map<String, Path> ARTIFACTS = artifacts();

    private static final Pattern AUTO_CONFIGURATION = Pattern.compile("@AutoConfiguration\\b");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    @Test
    void everyAutoConfigurationIsRegisteredExactlyOnceByItsOwnArtifact() {
        Map<String, TreeSet<String>> registrations = new TreeMap<>();
        ARTIFACTS.forEach((artifact, root) ->
            registrations.put(artifact, new TreeSet<>(declaredIn(root))));

        List<String> problems = new ArrayList<>();
        ARTIFACTS.forEach((artifact, root) -> {
            for (Path source : javaSources(root)) {
                if (!AUTO_CONFIGURATION.matcher(withoutComments(read(source))).find()) {
                    continue;
                }
                String fqn = packageOf(source) + "." + typeName(source);
                List<String> owners = registrations.entrySet().stream()
                    .filter(entry -> entry.getValue().contains(fqn))
                    .map(Map.Entry::getKey)
                    .toList();
                if (owners.isEmpty()) {
                    problems.add(fqn + " (" + artifact + ") не зарегистрирована: подсистема"
                        + " не подключится, и это не увидит компилятор");
                } else if (owners.size() > 1) {
                    problems.add(fqn + " зарегистрирована несколько раз: " + owners);
                } else if (!owners.get(0).equals(artifact)) {
                    problems.add(fqn + " объявлена в " + artifact + ", а зарегистрирована"
                        + " артефактом " + owners.get(0));
                }
            }
        });

        assertThat(problems)
            .as("регистрация авто-конфигураций распределена по артефактам: каждая должна быть"
                + " ровно у одного владельца — иначе потеря или двойное применение проходят"
                + " незаметно")
            .isEmpty();
    }

    @Test
    void everyRegisteredEntryPointsToAClassOfThatArtifact() {
        List<String> stale = new ArrayList<>();
        ARTIFACTS.forEach((artifact, root) -> {
            Path importsFile = importsFile(root);
            for (String fqn : declaredIn(root)) {
                Path source = root.resolve("src/main/java")
                    .resolve(fqn.replace('.', '/') + ".java");
                if (!Files.exists(source)) {
                    stale.add(artifact + ": " + fqn);
                }
            }
        });

        assertThat(stale)
            .as("запись без класса = падение на старте с ClassNotFoundException: тип уехал,"
                + " а регистрация осталась")
            .isEmpty();
    }

    @Test
    void anArtifactRegistersBeansOnlyIfItActuallyHasAutoConfigurations() {
        List<String> problems = new ArrayList<>();
        ARTIFACTS.forEach((artifact, root) -> {
            boolean hasAutoConfiguration = javaSources(root).stream()
                .anyMatch(source -> AUTO_CONFIGURATION.matcher(withoutComments(read(source))).find());
            boolean hasImportsFile = Files.exists(importsFile(root));
            if (hasAutoConfiguration && !hasImportsFile) {
                problems.add(artifact + " объявляет @AutoConfiguration, но imports-файла нет:"
                    + " подсистема молча не подключится");
            }
            if (!hasAutoConfiguration && hasImportsFile) {
                problems.add(artifact + " не объявляет авто-конфигураций, но несёт imports-файл:"
                    + " в артефакт попало конфигурирование контейнера");
            }
        });

        assertThat(problems)
            .as("наличие imports-файла — это и есть признак «артефакт несёт бины». Слой"
                + " контрактов его не несёт; runtime-, event- и persistence-модули — обязаны")
            .isEmpty();
    }

    private static List<String> declaredIn(Path root) {
        Path importsFile = importsFile(root);
        if (!Files.exists(importsFile)) {
            return List.of();
        }
        try {
            return Files.readAllLines(importsFile, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path importsFile(Path root) {
        return root.resolve("src/main/resources").resolve(IMPORTS_RESOURCE);
    }

    /** Приложение — это корень репозитория; модули находятся по имени каталога. */
    private static Map<String, Path> artifacts() {
        Map<String, Path> artifacts = new TreeMap<>();
        artifacts.put("application", Path.of("."));
        try (Stream<Path> dirs = Files.list(Path.of("."))) {
            dirs.filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().startsWith("platform-"))
                .filter(path -> Files.exists(path.resolve("pom.xml")))
                .sorted()
                .forEach(path -> artifacts.put(path.getFileName().toString(), path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return artifacts;
    }

    private static List<Path> javaSources(Path artifactRoot) {
        Path root = artifactRoot.resolve("src/main/java");
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String typeName(Path source) {
        String text = withoutComments(read(source));
        Matcher matcher = Pattern.compile("(?:class|interface|record|enum)\\s+(\\w+)").matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("нет объявления типа: " + source);
        }
        return matcher.group(1);
    }

    private static String packageOf(Path source) {
        Matcher matcher = PACKAGE.matcher(read(source));
        if (!matcher.find()) {
            throw new IllegalStateException("нет package: " + source);
        }
        return matcher.group(1);
    }

    private static String withoutComments(String text) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(text).replaceAll(" ")).replaceAll(" ");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
