package org.ipro;

import org.ipro.numbering.NumberingRuleService;
import org.ipro.numbering.NumberingService;
import org.ipro.numbering.annotation.Numbered;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 → D3: первый настоящий модуль-подсистема, а не срез контрактов и не капсула из одного
 * типа.
 *
 * <p>Предыдущие срезы проверяли по одному свойству каждый: контракты — что их читает
 * компилятор; events — что модуль регистрирует свои бины сам; persistence — что модуль сам
 * объявляет свои пакеты в persistence unit. Нумерация несёт все эти свойства сразу, и
 * именно поэтому она выбрана первой по замеру: после выноса позвоночника метаданных
 * ({@code platform-metadata}) у неё не осталось ни одной ссылки на дерево приложения.</p>
 *
 * <p>Что здесь проверяется и почему именно это:</p>
 * <ol>
 * <li>состав среза — reviewed-бюджет: подсистема выехала целиком (17 типов), а не половиной,
 *     иначе «модуль» остался бы папкой в дереве;</li>
 * <li>объявленные зависимости — reviewed: вниз (контракты, метаданные, persistence) и
 *     API-артефакты, но не вверх к {@code data}/{@code form}/{@code rls}/{@code telemetry};</li>
 * <li>регистрация: сущности и репозитории объявляет модуль, приложение и платформенный хаб
 *     их больше не перечисляют — иначе появились бы две декларации там, где раньше была
 *     одна, и «кто владелец» перестало бы быть проверяемым;</li>
 * <li>reflection-регистрация работает через артефакт: {@code @Numbered} стоит на сущностях
 *     приложения, а сканирует их платформенный сканер — то есть аннотация обязана грузиться
 *     из модуля, а не из дерева;</li>
 * <li>авто-конфигурация зарегистрирована <b>своим</b> imports-файлом, а не файлом
 *     приложения: требование roadmap «extraction не увеличивает число обязательных
 *     registrations в application».</li>
 * </ol>
 */
class PlatformNumberingModuleTest {

    private static final Path MODULE = Path.of("platform-numbering");

    private static final String IMPORTS_RESOURCE =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** Reviewed-бюджет среза: подсистема целиком, ни больше ни меньше. */
    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.numbering.NumberFormatter",
        "org.ipro.numbering.NumberingCounter",
        "org.ipro.numbering.NumberingCounterRepository",
        "org.ipro.numbering.NumberingCounterService",
        "org.ipro.numbering.NumberingDefinition",
        "org.ipro.numbering.NumberingMetadataRegistry",
        "org.ipro.numbering.NumberingPeriod",
        "org.ipro.numbering.NumberingRule",
        "org.ipro.numbering.NumberingRuleRepository",
        "org.ipro.numbering.NumberingRuleService",
        "org.ipro.numbering.NumberingScopeResolver",
        "org.ipro.numbering.NumberingService",
        "org.ipro.numbering.annotation.Numbered",
        "org.ipro.numbering.annotation.NumberingPolicies",
        "org.ipro.numbering.annotation.NumberingPolicy",
        "org.ipro.numbering.annotation.NumberingRole",
        "org.ipro.numbering.config.NumberingAutoConfiguration");

    private static final Set<String> REVIEWED_DEPENDENCIES = Set.of(
        "platform-contracts",
        "platform-metadata",
        "platform-persistence",
        "jakarta.persistence-api",
        "jakarta.validation-api",
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

        assertThat(actual)
            .as("срез подсистемы reviewed: половина подсистемы в модуле, половина в дереве"
                + " означала бы, что граница проходит внутри неё")
            .isEqualTo(new TreeSet<>(REVIEWED_TYPES));
    }

    @Test
    void moduleDeclaresOnlyReviewedDependencies() {
        Set<String> declared = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(read(MODULE.resolve("pom.xml")));
        while (matcher.find()) {
            String artifactId = matcher.group(1);
            if (!artifactId.equals("platform-numbering")
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
        String autoConfiguration =
            read(MODULE.resolve("src/main/java/org/ipro/numbering/config/NumberingAutoConfiguration.java"));
        assertThat(declaredAnnotations(autoConfiguration))
            .as("модуль обязан объявлять и persistence unit, и Spring Data: иначе его"
                + " сущности и репозитории остаются в артефакте без регистрации")
            .containsExactlyInAnyOrder("EntityScan", "EnableJpaRepositories");
        assertThat(autoConfiguration).contains("\"org.ipro.numbering\"");

        String application = read(Path.of("src/main/java/org/ip/Application.java"));
        assertThat(application)
            .as("приложение больше не перечисляет пакеты подсистемы")
            .doesNotContain("org.ipro.numbering");

        String hub = read(Path.of("src/main/java/org/ipro/rls/config/RlsAutoConfiguration.java"));
        assertThat(hub)
            .as("платформенный хаб репозиториев перечисляет только то, что ещё живёт в дереве")
            .doesNotContain("\"org.ipro.numbering\"");

        assertThat(lines(MODULE.resolve("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .containsExactly("org.ipro.numbering.config.NumberingAutoConfiguration");
        assertThat(lines(Path.of("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .as("приложение не должно регистрировать чужую авто-конфигурацию: модуль делает"
                + " это сам")
            .doesNotContain("org.ipro.numbering.config.NumberingAutoConfiguration");
    }

    @Test
    void subsystemLeftTheApplicationTree() {
        assertThat(Path.of("src/main/java/org/ipro/numbering")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/numbering/NumberingService.java")).doesNotExist();
    }

    /**
     * Файловые проверки говорят, где типы объявлены; эта — откуда они берутся в рантайме.
     * Для нумерации это не формальность: {@code @Numbered} стоит на сущностях приложения,
     * и сканер метаданных читает аннотацию с класса, приехавшего из артефакта.
     */
    @Test
    void subsystemClassesAreResolvedFromTheArtifactAtRuntime() {
        for (Class<?> type : List.of(NumberingService.class, NumberingRuleService.class, Numbered.class)) {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            assertThat(codeSource).as("%s должен грузиться из артефакта", type.getName()).isNotNull();
            assertThat(codeSource.getLocation().toString())
                .as("%s: класс обязан приходить из platform-numbering, а не из target/classes",
                    type.getName())
                .contains("platform-numbering");
        }
    }

    private static List<String> declaredAnnotations(String text) {
        Matcher matcher = ANNOTATION.matcher(text);
        java.util.List<String> annotations = new java.util.ArrayList<>();
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
