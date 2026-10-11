package org.ipro.rest.web;

import org.ipro.rest.config.RestReadAutoConfiguration;
import org.ipro.rest.web.config.RestRlsResolverAutoConfiguration;
import org.ipro.rest.web.config.RestWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner-side guard for the optional REST read HTTP adapter artifact (F-REST-READ-4 §3, §9 4.1).
 *
 * <p>The artifact is born with its reviewed surface and dependency closure: routes, security and
 * Problem Details land later, but the boundary — what the module publishes, which stacks it is
 * allowed to resolve, how it is opted into — is fixed from day one.</p>
 */
class RestWebModuleCompositionTest {

    private static final Path MODULE = Path.of("").toAbsolutePath();
    private static final Path WORKSPACE = MODULE.getParent();

    private static final Set<String> REVIEWED_TOP_LEVEL_API = Set.of(
        "RestRlsResolverAutoConfiguration",
        "RestWebAutoConfiguration",
        "RestReadController",
        "RestOpenApiGenerator",
        "RestJsonCodec",
        "RestProblemFactory",
        "RestProblemWriter",
        "RestReadExceptionHandler",
        "RestQueryParser",
        "RestQueryException",
        "RestQueryReason",
        // 4.3: request RLS integration, namespace filters and the Basic read chain wiring.
        "RestAwareRlsReadableIdsResolver",
        "RestReadableIdsCache",
        "RestRequestContext",
        "RestReadNamespaceFilter",
        "RestRequestScopeFilter",
        "RestRequestTargetParser");
    private static final Set<String> REVIEWED_API_TYPES = Set.of(
        "org.ipro.rest.web.config.RestRlsResolverAutoConfiguration",
        "org.ipro.rest.web.config.RestWebAutoConfiguration",
        "org.ipro.rest.web.mvc.RestReadController",
        "org.ipro.rest.web.openapi.RestOpenApiGenerator",
        "org.ipro.rest.web.codec.RestJsonCodec",
        "org.ipro.rest.web.problem.RestProblemFactory",
        "org.ipro.rest.web.problem.RestProblemWriter",
        "org.ipro.rest.web.problem.RestReadExceptionHandler",
        "org.ipro.rest.web.query.RestQueryParser",
        "org.ipro.rest.web.query.RestQueryException",
        "org.ipro.rest.web.query.RestQueryReason",
        "org.ipro.rest.web.rls.RestAwareRlsReadableIdsResolver",
        "org.ipro.rest.web.rls.RestReadableIdsCache",
        "org.ipro.rest.web.rls.RestRequestContext",
        "org.ipro.rest.web.security.RestReadNamespaceFilter",
        "org.ipro.rest.web.security.RestRequestScopeFilter",
        "org.ipro.rest.web.security.RestRequestTargetParser");

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern TOP_LEVEL_PUBLIC_TYPE = Pattern.compile(
        "^public\\s+(?:final\\s+)?(?:class|enum|record)\\s+(\\w+)", Pattern.MULTILINE);
    // Maven's JSON dependency tree writes groupId and artifactId adjacently for each node.
    private static final Pattern TREE_COORDINATE = Pattern.compile(
        "\"groupId\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"artifactId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern APPLICATION_PACKAGE = Pattern.compile("(?<![\\w.])org\\.ip(?![\\w])");

    @Test
    void publishesOnlyTheReviewedAdapterApi() {
        Set<String> actual = new TreeSet<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            Matcher matcher = TOP_LEVEL_PUBLIC_TYPE.matcher(read(source));
            while (matcher.find()) actual.add(matcher.group(1));
        }

        assertThat(actual).isEqualTo(new TreeSet<>(REVIEWED_TOP_LEVEL_API));
    }

    @Test
    void everyPublishedTypeHasAReviewedRoleAndNoApplicationRole() {
        Map<String, String> roles = new LinkedHashMap<>();
        Path registry = WORKSPACE.resolve("src/test/resources/platform-rest-web-surface.txt");
        for (String line : read(registry).lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\s+", 2);
            roles.put(parts[1], parts[0]);
        }

        assertThat(roles.keySet()).isEqualTo(REVIEWED_API_TYPES);
        assertThat(roles.values()).contains("INTERNAL").doesNotContain("APP_API", "MODULE_API");
    }

    @Test
    void productionSourcesStayInsideTheAdapterBoundary() {
        List<String> foreignImports = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            Matcher matcher = IMPORT.matcher(read(source));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (!imported.startsWith("java.")
                    && !imported.startsWith("jakarta.servlet.")
                    && !imported.startsWith("org.ipro.")
                    && !imported.startsWith("org.springframework.")
                    && !imported.startsWith("com.fasterxml.jackson.annotation.")
                    && !imported.startsWith("tools.jackson.")
                    && !imported.startsWith("org.slf4j.")) {
                    foreignImports.add(source.getFileName() + " -> " + imported);
                }
            }
        }
        assertThat(foreignImports).isEmpty();

        List<Path> applicationReferences = javaSources(MODULE.resolve("src/main/java")).stream()
            .filter(source -> APPLICATION_PACKAGE.matcher(read(source)).find())
            .toList();
        assertThat(applicationReferences).isEmpty();

        List<String> foreignStacks = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            String text = read(source).toLowerCase(Locale.ROOT);
            if (text.contains("vaadin")
                || text.contains("webflux")
                || text.contains("org.springframework.web.reactive")
                || text.contains("springframework.data.rest")) {
                foreignStacks.add(source.getFileName().toString());
            }
        }
        assertThat(foreignStacks).isEmpty();

        String pom = read(MODULE.resolve("pom.xml"));
        assertThat(pom).doesNotContain("spring-boot-starter-web", "spring-data-rest", "webflux", "vaadin");
    }

    @Test
    void resolvedMavenCompileClasspathMatchesReviewedClosureAndCarriesNoForeignStacks() {
        Path dependencyTree = MODULE.resolve("target/rest-web-compile-dependency-tree.json");
        assertThat(dependencyTree)
            .as("Maven должен записать реальный разрешённый compile-граф до запуска тестов")
            .exists();

        Set<String> resolved = new TreeSet<>();
        Matcher coordinates = TREE_COORDINATE.matcher(read(dependencyTree));
        while (coordinates.find()) {
            resolved.add(coordinates.group(1) + ":" + coordinates.group(2));
        }

        assertThat(resolved)
            .as("изменение compile classpath требует review и явного обновления rest-web-compile-dependencies.txt")
            .isEqualTo(reviewedCompileCoordinates());
        assertThat(resolved).contains("org.ipro:platform-rest-web", "org.ipro:platform-rest",
            "org.ipro:platform-rls",
            "org.springframework:spring-webmvc", "org.springframework.security:spring-security-web",
            "org.springframework.security:spring-security-config");

        Set<String> forbidden = resolved.stream()
            .filter(RestWebModuleCompositionTest::isForeignStackCoordinate)
            .collect(Collectors.toCollection(TreeSet::new));
        assertThat(forbidden)
            .as("web-адаптер не приносит Vaadin, WebFlux/Netty, Spring Data REST, отчётные библиотеки"
                + " или контейнер сервлетов: их выбирает приложение")
            .isEmpty();
    }

    private static boolean isForeignStackCoordinate(String coordinate) {
        int separator = coordinate.indexOf(':');
        String groupId = coordinate.substring(0, separator).toLowerCase(Locale.ROOT);
        String artifactId = coordinate.substring(separator + 1).toLowerCase(Locale.ROOT);
        return groupId.startsWith("com.vaadin")
            || artifactId.startsWith("vaadin")
            || artifactId.contains("webflux")
            || (artifactId.contains("netty") && artifactId.contains("http"))
            || artifactId.startsWith("spring-data-rest")
            || artifactId.contains("ureport")
            || artifactId.contains("dynamicreports")
            || artifactId.contains("reportui")
            || artifactId.contains("jasperreports")
            || artifactId.contains("tomcat")
            || artifactId.contains("jetty")
            || artifactId.contains("undertow");
    }

    @Test
    void crossModuleRestImportsUseReviewedApiExceptConfigurationOrdering() {
        Map<String, String> roles = new LinkedHashMap<>();
        for (String line : read(WORKSPACE.resolve("src/test/resources/platform-rest-surface.txt"))
                .lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\s+", 2);
            roles.put(parts[1], parts[0]);
        }
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            Matcher imports = IMPORT.matcher(read(source));
            while (imports.find()) {
                String imported = imports.group(1);
                if (!imported.startsWith("org.ipro.rest.") || imported.startsWith("org.ipro.rest.web.")) continue;
                if (imported.equals(RestReadAutoConfiguration.class.getName())) continue;
                assertThat(roles.get(imported)).as("%s -> %s", source.getFileName(), imported)
                    .isIn("MODULE_API", "APP_API");
            }
        }
    }

    @Test
    void ownsAndOrdersItsAutoConfigurationRegistry() {
        Path imports = MODULE.resolve(
            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(read(imports).lines().toList())
            .containsExactlyInAnyOrder(RestWebAutoConfiguration.class.getName(),
                RestRlsResolverAutoConfiguration.class.getName());

        assertThat(RestWebAutoConfiguration.class.getAnnotation(AutoConfiguration.class)).isNotNull();
        AutoConfigureAfter order = RestWebAutoConfiguration.class.getAnnotation(AutoConfigureAfter.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).contains(RestReadAutoConfiguration.class);

        // Резолвер обязан регистрироваться до RlsAutoConfiguration, иначе default-бин
        // не увидит его и появится второй независимый источник (AutoConfigure cycle
        // запрещает держать оба порядка в одном классе).
        assertThat(RestRlsResolverAutoConfiguration.class.getAnnotation(AutoConfiguration.class)).isNotNull();
        AutoConfigureBefore before =
            RestRlsResolverAutoConfiguration.class.getAnnotation(AutoConfigureBefore.class);
        assertThat(before).isNotNull();
        assertThat(before.value()).contains(org.ipro.rls.config.RlsAutoConfiguration.class);
    }

    @Test
    void remainsAnExplicitOptInAddOnAndBothStartersStayClean() {
        String applicationPom = read(WORKSPACE.resolve("pom.xml"));
        String backendStarter = read(WORKSPACE.resolve("platform-backend-starter/pom.xml"));
        String vaadinStarter = read(WORKSPACE.resolve("platform-vaadin-starter/pom.xml"));
        String bom = read(WORKSPACE.resolve("platform-bom/pom.xml"));

        assertThat(applicationPom).contains("<artifactId>platform-rest-web</artifactId>");
        assertThat(bom).contains("<artifactId>platform-rest-web</artifactId>");
        assertThat(backendStarter).doesNotContain("platform-rest-web");
        assertThat(vaadinStarter).doesNotContain("platform-rest-web");
    }

    private static Set<String> reviewedCompileCoordinates() {
        Path manifest = MODULE.resolve("src/test/resources/rest-web-compile-dependencies.txt");
        Set<String> coordinates = new TreeSet<>();
        for (String line : read(manifest).lines().toList()) {
            String coordinate = line.trim();
            if (!coordinate.isEmpty() && !coordinate.startsWith("#")) coordinates.add(coordinate);
        }
        return coordinates;
    }

    private static List<Path> javaSources(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
