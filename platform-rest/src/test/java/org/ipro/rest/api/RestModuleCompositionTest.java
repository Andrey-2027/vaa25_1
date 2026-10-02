package org.ipro.rest.api;

import org.junit.jupiter.api.Test;
import org.ipro.data.config.DataAccessAutoConfiguration;
import org.ipro.rest.config.RestResourceCatalogAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Owner-side guard for the optional REST declaration artifact. */
class RestModuleCompositionTest {

    private static final Path MODULE = Path.of("").toAbsolutePath();
    private static final Path WORKSPACE = MODULE.getParent();
    private static final Set<String> REVIEWED_TOP_LEVEL_API = Set.of(
        "RestFieldFormat",
        "RestFieldType",
        "RestFilterOperator",
        "RestNullability",
        "RestPropertyPath",
        "RestResourceBuilder",
        "RestResourceDeclarationException",
        "RestResourceDefinition",
        "RestResources",
        "RestSortDirection",
        "RestResourceKey",
        "RestReadOperation",
        "RestPathUsage",
        "RestResourceCatalogException",
        "ResolvedRestPath",
        "ResolvedRestField",
        "RestResourceProjection",
        "RestAttributeRequirement",
        "RestResourceAccessRequirements",
        "ResolvedRestResource",
        "RestResourceCatalog",
        "RestResourceCatalogAutoConfiguration",
        "RestReadAutoConfiguration",
        "C5RestOperationRegistrar",
        "RestReferenceAuthorizationValidator",
        "RestReadOutcome",
        "RestReadException",
        "RestListQuery",
        "RestDetailQuery",
        "RestPageResult",
        "RestDetailResult",
        "RestFilterSpecificationCompiler",
        "RestScalarProjector",
        "RestReadService");
    private static final Set<String> REVIEWED_API_TYPES = Set.of(
        "org.ipro.rest.api.RestFieldFormat",
        "org.ipro.rest.api.RestFieldType",
        "org.ipro.rest.api.RestFilterOperator",
        "org.ipro.rest.api.RestNullability",
        "org.ipro.rest.api.RestPropertyPath",
        "org.ipro.rest.api.RestResourceBuilder",
        "org.ipro.rest.api.RestResourceDeclarationException",
        "org.ipro.rest.api.RestResourceDeclarationException.Code",
        "org.ipro.rest.api.RestResourceDefinition",
        "org.ipro.rest.api.RestResourceDefinition.Field",
        "org.ipro.rest.api.RestResourceDefinition.Filter",
        "org.ipro.rest.api.RestResourceDefinition.Sort",
        "org.ipro.rest.api.RestResources",
        "org.ipro.rest.api.RestSortDirection",
        "org.ipro.rest.catalog.RestResourceKey",
        "org.ipro.rest.catalog.RestReadOperation",
        "org.ipro.rest.catalog.RestPathUsage",
        "org.ipro.rest.catalog.RestResourceCatalogException",
        "org.ipro.rest.catalog.RestResourceCatalogException.Code",
        "org.ipro.rest.catalog.ResolvedRestPath",
        "org.ipro.rest.catalog.ResolvedRestPath.Segment",
        "org.ipro.rest.catalog.ResolvedRestField",
        "org.ipro.rest.catalog.RestResourceProjection",
        "org.ipro.rest.catalog.RestAttributeRequirement",
        "org.ipro.rest.catalog.RestResourceAccessRequirements",
        "org.ipro.rest.catalog.ResolvedRestResource",
        "org.ipro.rest.catalog.RestResourceCatalog",
        "org.ipro.rest.config.RestResourceCatalogAutoConfiguration",
        "org.ipro.rest.config.RestReadAutoConfiguration",
        "org.ipro.rest.security.C5RestOperationRegistrar",
        "org.ipro.rest.security.RestReferenceAuthorizationValidator",
        "org.ipro.rest.service.RestReadOutcome",
        "org.ipro.rest.service.RestReadException",
        "org.ipro.rest.service.RestListQuery",
        "org.ipro.rest.service.RestDetailQuery",
        "org.ipro.rest.service.RestPageResult",
        "org.ipro.rest.service.RestDetailResult",
        "org.ipro.rest.service.RestFilterSpecificationCompiler",
        "org.ipro.rest.service.RestScalarProjector",
        "org.ipro.rest.service.RestReadService");
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern TOP_LEVEL_PUBLIC_TYPE = Pattern.compile(
        "^public\\s+(?:final\\s+)?(?:class|enum|record)\\s+(\\w+)", Pattern.MULTILINE);
    // Maven's JSON dependency tree writes groupId and artifactId adjacently for each node.
    private static final Pattern TREE_COORDINATE = Pattern.compile(
        "\"groupId\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"artifactId\"\\s*:\\s*\"([^\"]+)\"");

    @Test
    void publishesOnlyTheReviewedApplicationDeclarationApi() {
        Set<String> actual = new TreeSet<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            Matcher matcher = TOP_LEVEL_PUBLIC_TYPE.matcher(read(source));
            while (matcher.find()) actual.add(matcher.group(1));
        }

        assertThat(actual).isEqualTo(new TreeSet<>(REVIEWED_TOP_LEVEL_API));
    }

    @Test
    void declarationApiRemainsIndependentAndRuntimeCatalogUsesOnlyReviewedBoundaries() {
        List<String> foreignImports = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java/org/ipro/rest/api"))) {
            Matcher matcher = IMPORT.matcher(read(source));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (!imported.startsWith("java.")
                    && !imported.startsWith("org.ipro.rest.api.")) {
                    foreignImports.add(source.getFileName() + " -> " + imported);
                }
            }
        }
        assertThat(foreignImports).isEmpty();

        List<String> unreviewedCatalogImports = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java/org/ipro/rest/catalog"))) {
            Matcher matcher = IMPORT.matcher(read(source));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (!imported.startsWith("java.")
                    && !imported.startsWith("jakarta.persistence.")
                    && !imported.startsWith("org.hibernate.")
                    && !imported.startsWith("org.ipro.data.")
                    && !imported.startsWith("org.ipro.fetch.plan.")
                    && !imported.startsWith("org.ipro.rest.api.")
                    && !imported.startsWith("org.ipro.rest.catalog.")) {
                    unreviewedCatalogImports.add(source.getFileName() + " -> " + imported);
                }
            }
        }
        assertThat(unreviewedCatalogImports).isEmpty();

        List<String> unreviewedConfigurationImports = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java/org/ipro/rest/config"))) {
            Matcher matcher = IMPORT.matcher(read(source));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (!imported.startsWith("java.")
                    && !imported.startsWith("jakarta.persistence.")
                    && !imported.startsWith("org.ipro.data.")
                    && !imported.startsWith("org.ipro.rest.api.")
                    && !imported.startsWith("org.ipro.rest.catalog.")
                    && !imported.startsWith("org.ipro.rest.security.")
                    && !imported.startsWith("org.ipro.rest.service.")
                    && !imported.startsWith("org.ipro.rls.")
                    && !imported.startsWith("org.springframework.")) {
                    unreviewedConfigurationImports.add(source.getFileName() + " -> " + imported);
                }
            }
        }
        assertThat(unreviewedConfigurationImports).isEmpty();

        String pom = read(MODULE.resolve("pom.xml"));
        assertThat(pom).doesNotContain("spring-boot-starter-web", "spring-webmvc", "vaadin");
    }

    @Test
    void resolvedMavenCompileClasspathMatchesReviewedClosureAndExcludesHttpStacks() {
        Path dependencyTree = MODULE.resolve("target/rest-compile-dependency-tree.json");
        assertThat(dependencyTree)
            .as("Maven должен записать реальный разрешённый compile-граф до запуска тестов")
            .exists();

        Set<String> resolved = new TreeSet<>();
        Matcher coordinates = TREE_COORDINATE.matcher(read(dependencyTree));
        while (coordinates.find()) {
            resolved.add(coordinates.group(1) + ":" + coordinates.group(2));
        }

        Set<String> reviewed = reviewedCompileCoordinates();
        assertThat(resolved)
            .as("изменение compile classpath требует review и явного обновления rest-compile-dependencies.txt")
            .isEqualTo(reviewed);
        assertThat(resolved).contains("org.ipro:platform-rest", "org.ipro:platform-core",
            "org.ipro:platform-spring-boot-autoconfigure", "org.hibernate.orm:hibernate-core",
            "jakarta.persistence:jakarta.persistence-api");
        Set<String> forbiddenHttp = resolved.stream()
            .filter(RestModuleCompositionTest::isForbiddenHttpCoordinate)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        assertThat(forbiddenHttp)
            .as("platform-rest is a declaration/catalog module and must not resolve an HTTP or servlet stack")
            .isEmpty();
    }

    private static Set<String> reviewedCompileCoordinates() {
        Path manifest = MODULE.resolve("src/test/resources/rest-compile-dependencies.txt");
        Set<String> coordinates = new TreeSet<>();
        for (String line : read(manifest).lines().toList()) {
            String coordinate = line.trim();
            if (!coordinate.isEmpty() && !coordinate.startsWith("#")) coordinates.add(coordinate);
        }
        return coordinates;
    }

    private static boolean isForbiddenHttpCoordinate(String coordinate) {
        String artifactId = coordinate.substring(coordinate.indexOf(':') + 1);
        return artifactId.startsWith("spring-web")
            || artifactId.startsWith("spring-boot-starter-web")
            || artifactId.startsWith("vaadin")
            || artifactId.contains("servlet")
            || artifactId.contains("tomcat")
            || artifactId.contains("jetty")
            || artifactId.contains("undertow")
            || (artifactId.contains("netty") && artifactId.contains("http"));
    }

    @Test
    void everyPublishedTypeHasAReviewedRoleAndSignatureBaseline() {
        Map<String, String> roles = new LinkedHashMap<>();
        Path registry = WORKSPACE.resolve("src/test/resources/platform-rest-surface.txt");
        for (String line : read(registry).lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\s+", 2);
            roles.put(parts[1], parts[0]);
        }

        assertThat(roles.keySet()).isEqualTo(REVIEWED_API_TYPES);
        assertThat(roles.values()).contains("APP_API", "INTERNAL")
            .doesNotContain("MODULE_API");
        assertThat(WORKSPACE.resolve("src/test/resources/platform-api-baseline/platform-rest.api"))
            .as("публичные сигнатуры platform-rest должны быть зафиксированы общим baseline")
            .exists();
    }

    @Test
    void remainsAnExplicitOptionalDependencyAndHasNoHttpAdapter() {
        String applicationPom = read(WORKSPACE.resolve("pom.xml"));
        String backendStarter = read(WORKSPACE.resolve("platform-backend-starter/pom.xml"));
        String vaadinStarter = read(WORKSPACE.resolve("platform-vaadin-starter/pom.xml"));
        String bom = read(WORKSPACE.resolve("platform-bom/pom.xml"));

        assertThat(applicationPom).contains("<artifactId>platform-rest</artifactId>");
        assertThat(bom).contains("<artifactId>platform-rest</artifactId>");
        assertThat(backendStarter).doesNotContain("platform-rest");
        assertThat(vaadinStarter).doesNotContain("platform-rest");
        assertThat(javaSources(MODULE.resolve("src/main/java")))
            .allSatisfy(source -> assertThat(read(source))
                .doesNotContain("@RestController", "@RequestMapping", "DispatcherServlet"));
    }

    @Test
    void ownsAndOrdersItsCatalogAutoConfigurationAfterBackendDataAccess() {
        Path imports = MODULE.resolve(
            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(read(imports).lines().toList())
            .containsExactly(RestResourceCatalogAutoConfiguration.class.getName(), org.ipro.rest.config.RestReadAutoConfiguration.class.getName());
        AutoConfigureAfter order = RestResourceCatalogAutoConfiguration.class
            .getAnnotation(AutoConfigureAfter.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).contains(DataAccessAutoConfiguration.class);
    }

    @Test
    void productionSourcesDoNotReferToApplicationTypes() {
        Pattern applicationPackage = Pattern.compile("(?<![\\w.])org\\.ip(?![\\w])");
        List<Path> references = javaSources(MODULE.resolve("src/main/java")).stream()
            .filter(source -> applicationPackage.matcher(read(source)).find())
            .toList();
        assertThat(references).isEmpty();
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
