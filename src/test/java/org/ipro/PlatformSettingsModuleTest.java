package org.ipro;

import org.ipro.settings.SettingsRegistry;
import org.ipro.settings.SettingsService;
import org.ipro.settings.setting.Setting;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 → D3: второй модуль-подсистема после нумерации.
 *
 * <p>Нумерация доказала, что подсистема с сущностями, репозиториями, бинами и reflection
 * может жить вне дерева. Константы нужны не как ещё одно доказательство того же, а как
 * проверка воспроизводимости приёма на подсистеме другого профиля: здесь есть каталог,
 * который сам сканирует пакет приложения, каталог значений по умолчанию и обратная ссылка в
 * индекс метаданных ({@code settings → metadata}).</p>
 *
 * <p>Замыкание констант на дерево до среза было ровно два типа — те самые сканер и индекс,
 * что уехали в {@code platform-metadata}; поэтому срез обнулил его и снял запись о подсистеме
 * с реестра {@code PlatformSubsystemClosureTest}.</p>
 */
class PlatformSettingsModuleTest {

    private static final Path MODULE = Path.of("platform-settings");

    private static final String IMPORTS_RESOURCE =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** Reviewed-бюджет среза: подсистема целиком. */
    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.settings.SettingValue",
        "org.ipro.settings.SettingValueRepository",
        "org.ipro.settings.SettingsRegistry",
        "org.ipro.settings.SettingsReverseReferenceSource",
        "org.ipro.settings.SettingsService",
        "org.ipro.settings.config.SettingsAutoConfiguration",
        "org.ipro.settings.setting.Setting",
        "org.ipro.settings.setting.SettingsGroup");

    private static final Set<String> REVIEWED_DEPENDENCIES = Set.of(
        "platform-contracts",
        "platform-metadata",
        "platform-persistence",
        "jakarta.persistence-api",
        "spring-data-jpa",
        "spring-boot-autoconfigure",
        "spring-boot-persistence");

    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern ANNOTATION = Pattern.compile(
        "^\\s*@(EntityScan|EnableJpaRepositories)\\b", Pattern.MULTILINE);

    @Test
    void moduleCarriesTheWholeSubsystem() {
        Set<String> actual = new TreeSet<>();
        for (Path source : javaSources()) {
            actual.add(packageOf(source) + "." + typeOf(source));
        }

        assertThat(actual).isEqualTo(new TreeSet<>(REVIEWED_TYPES));
    }

    @Test
    void moduleDeclaresOnlyReviewedDependencies() {
        Set<String> declared = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(read(MODULE.resolve("pom.xml")));
        while (matcher.find()) {
            String artifactId = matcher.group(1);
            if (!artifactId.equals("platform-settings")
                    && !artifactId.equals("spring-boot-starter-parent")) {
                declared.add(artifactId);
            }
        }

        assertThat(declared).isEqualTo(new TreeSet<>(REVIEWED_DEPENDENCIES));
        String pom = read(MODULE.resolve("pom.xml"));
        assertThat(pom).doesNotContain("<groupId>org.ip</groupId>");
        assertThat(pom).doesNotContain("<artifactId>Vaa25_1</artifactId>");
    }

    @Test
    void moduleOwnsItsPersistenceAndTheApplicationNoLongerDoes() {
        String autoConfiguration = read(
            MODULE.resolve("src/main/java/org/ipro/settings/config/SettingsAutoConfiguration.java"));
        assertThat(declaredAnnotations(autoConfiguration))
            .as("модуль обязан объявлять и persistence unit, и Spring Data")
            .containsExactlyInAnyOrder("EntityScan", "EnableJpaRepositories");
        assertThat(autoConfiguration).contains("\"org.ipro.settings\"");

        assertThat(read(Path.of("src/main/java/org/ip/Application.java")))
            .as("приложение больше не перечисляет пакеты подсистемы")
            .doesNotContain("org.ipro.settings");

        assertThat(read(Path.of("src/main/java/org/ipro/rls/config/RlsAutoConfiguration.java")))
            .as("платформенный хаб репозиториев перечисляет только то, что ещё живёт в дереве")
            .doesNotContain("\"org.ipro.settings\"");

        assertThat(lines(MODULE.resolve("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .containsExactly("org.ipro.settings.config.SettingsAutoConfiguration");
        assertThat(lines(Path.of("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .doesNotContain("org.ipro.settings.config.SettingsAutoConfiguration");
    }

    @Test
    void subsystemLeftTheApplicationTree() {
        assertThat(Path.of("src/main/java/org/ipro/settings")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/settings/SettingsRegistry.java")).doesNotExist();
    }

    /**
     * Файловые проверки говорят, где типы объявлены; эта — откуда они берутся в рантайме.
     * Для констант это существенно: {@code @Setting}/{@code @SettingsGroup} стоят на
     * appdev-классах приложения, а читает их каталог из артефакта.
     */
    @Test
    void subsystemClassesAreResolvedFromTheArtifactAtRuntime() {
        for (Class<?> type : List.of(SettingsService.class, SettingsRegistry.class, Setting.class)) {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            assertThat(codeSource).as("%s должен грузиться из артефакта", type.getName()).isNotNull();
            assertThat(codeSource.getLocation().toString())
                .as("%s: класс обязан приходить из platform-settings, а не из target/classes",
                    type.getName())
                .contains("platform-settings");
        }
    }

    private static List<String> declaredAnnotations(String text) {
        Matcher matcher = ANNOTATION.matcher(text);
        List<String> annotations = new ArrayList<>();
        while (matcher.find()) {
            annotations.add(matcher.group(1));
        }
        return annotations;
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> javaSources() {
        Path sources = MODULE.resolve("src/main/java");
        try (Stream<Path> files = Files.walk(sources)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String typeOf(Path source) {
        String name = source.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    private static String packageOf(Path source) {
        Matcher matcher = PACKAGE.matcher(read(source));
        if (!matcher.find()) {
            throw new IllegalStateException("Нет package в " + source);
        }
        return matcher.group(1);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
