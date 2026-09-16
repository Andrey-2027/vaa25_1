package org.ipro;

import org.ipro.rls.AccessGrant;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.rls.config.RlsAutoConfiguration;
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
 * D2 → D3: четвёртый модуль-подсистема — Row-Level Security целиком.
 *
 * <p>Что здесь проверяется и почему именно это:</p>
 * <ol>
 * <li>состав среза — reviewed-бюджет: подсистема выехала целиком (36 типов: 33 класса
 *     RLS, 2 нейтральных SPI, persistence-конфигурация), а не половиной;</li>
 * <li>объявленные зависимости — reviewed: вниз (persistence, numbering-контракт,
 *     telemetry-артефакт) и API-артефакты, но не вверх к {@code metadata}/
 *     {@code fetch}/{@code reportstudio}/{@code ureport} и не к приложению;</li>
 * <li>регистрация: persistence-объявления держит {@code RlsPersistenceAutoConfiguration},
 *     а не functional {@code RlsAutoConfiguration} и не приложение/хаб;</li>
 * <li>обе авто-конфигурации зарегистрированы <b>своим</b> imports-файлом;</li>
 * <li>свойство сканирования измерений без default — его задаёт приложение.</li>
 * </ol>
 */
class PlatformRlsModuleTest {

    private static final Path MODULE = Path.of("platform-rls");

    private static final String IMPORTS_RESOURCE =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** Reviewed-бюджет среза: подсистема целиком, ни больше ни меньше. */
    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.rls.AccessGrant",
        "org.ipro.rls.AccessGrantChangeListener",
        "org.ipro.rls.AccessGrantRepository",
        "org.ipro.rls.AccessGrantVersion",
        "org.ipro.rls.AccessService",
        "org.ipro.rls.config.RlsAutoConfiguration",
        "org.ipro.rls.config.RlsPersistenceAutoConfiguration",
        "org.ipro.rls.RlsAccessDeniedException",
        "org.ipro.rls.RlsBypassAudit",
        "org.ipro.rls.RlsBypassScope",
        "org.ipro.rls.RlsCheckValue",
        "org.ipro.rls.RlsContext",
        "org.ipro.rls.RlsCurrentUser",
        "org.ipro.rls.RlsDimension",
        "org.ipro.rls.RlsDimensionKind",
        "org.ipro.rls.RlsDimensionRegistry",
        "org.ipro.rls.RlsDimensions",
        "org.ipro.rls.RlsDimensionValue",
        "org.ipro.rls.RlsDimensionValueCatalog",
        "org.ipro.rls.RlsDimensionValueLabelResolver",
        "org.ipro.rls.RlsFilterActivator",
        "org.ipro.rls.RlsGuardRequestFilter",
        "org.ipro.rls.RlsHibernateWriteGuardInstaller",
        "org.ipro.rls.RlsOwnedSectionLookup",
        "org.ipro.rls.RlsPolicyDescriptor",
        "org.ipro.rls.RlsPolicyEnforcer",
        "org.ipro.rls.RlsReadableIdsCache",
        "org.ipro.rls.RlsReadGate",
        "org.ipro.rls.RlsRepositoryEnforcementAspect",
        "org.ipro.rls.RlsRoleResolver",
        "org.ipro.rls.RlsScopeResolver",
        "org.ipro.rls.RlsStatementGuard",
        "org.ipro.rls.RlsUiGate",
        "org.ipro.rls.RlsWriteAuthorization",
        "org.ipro.rls.RlsWriteEnforcementListener",
        "org.ipro.rls.RlsWriteGuardBridge");

    private static final Set<String> REVIEWED_DEPENDENCIES = Set.of(
        "platform-persistence",
        "platform-numbering",
        "platform-telemetry",
        "jakarta.persistence-api",
        "jakarta.validation-api",
        "hibernate-core",
        "spring-data-jpa",
        "spring-tx",
        "spring-web",
        "spring-security-core",
        "spring-boot-autoconfigure",
        "spring-boot-persistence",
        "aspectjweaver",
        "jakarta.servlet-api",
        "slf4j-api",
        "spring-boot-starter-test");

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
            if (!artifactId.equals("platform-rls")
                    && !artifactId.equals("spring-boot-starter-parent")) {
                declared.add(artifactId);
            }
        }

        assertThat(declared).isEqualTo(new TreeSet<>(REVIEWED_DEPENDENCIES));
        String pom = read(MODULE.resolve("pom.xml"));
        assertThat(pom).doesNotContain("<groupId>org.ip</groupId>");
        assertThat(pom).doesNotContain("<artifactId>Vaa25_1</artifactId>");
        assertThat(pom).doesNotContain("platform-metadata");
        assertThat(pom).doesNotContain("vaadin");
    }

    @Test
    void moduleOwnsItsPersistenceAndTheApplicationNoLongerDoes() {
        String persistence =
            read(MODULE.resolve("src/main/java/org/ipro/rls/config/RlsPersistenceAutoConfiguration.java"));
        assertThat(declaredAnnotations(persistence))
            .as("persistence-регистрацию держит отдельный класс модуля, чтобы срезы"
                + " подключали только её")
            .containsExactlyInAnyOrder("EntityScan", "EnableJpaRepositories");
        assertThat(persistence).contains("\"org.ipro.rls\"");

        String functional =
            read(MODULE.resolve("src/main/java/org/ipro/rls/config/RlsAutoConfiguration.java"));
        assertThat(declaredAnnotations(functional))
            .as("functional конфигурация persistence-пакеты не объявляет")
            .isEmpty();
        assertThat(functional).doesNotContain("rls.dimension-scan-package:org.ip");

        String application = read(Path.of("src/main/java/org/ip/Application.java"));
        assertThat(application)
            .as("приложение больше не перечисляет пакеты подсистемы")
            .doesNotContain("org.ipro.rls");

        assertThat(lines(MODULE.resolve("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .containsExactlyInAnyOrder(
                "org.ipro.rls.config.RlsAutoConfiguration",
                "org.ipro.rls.config.RlsPersistenceAutoConfiguration");
        assertThat(lines(Path.of("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .as("приложение не должно регистрировать чужую авто-конфигурацию: модуль делает"
                + " это сам")
            .doesNotContain("org.ipro.rls.config.RlsAutoConfiguration")
            .doesNotContain("org.ipro.rls.config.RlsPersistenceAutoConfiguration");
    }

    @Test
    void subsystemLeftTheApplicationTree() {
        assertThat(Path.of("src/main/java/org/ipro/rls/AccessGrant.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/rls/RlsPolicyEnforcer.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/rls/config/RlsAutoConfiguration.java")).doesNotExist();
    }

    /**
     * Файловые проверки говорят, где типы объявлены; эта — откуда они берутся в рантайме.
     */
    @Test
    void subsystemClassesAreResolvedFromTheArtifactAtRuntime() {
        for (Class<?> type : List.of(AccessGrant.class, RlsPolicyEnforcer.class,
                RlsAutoConfiguration.class)) {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            assertThat(codeSource).as("%s должен грузиться из артефакта", type.getName()).isNotNull();
            assertThat(codeSource.getLocation().toString())
                .as("%s: класс обязан приходить из platform-rls, а не из target/classes",
                    type.getName())
                .contains("platform-rls");
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
