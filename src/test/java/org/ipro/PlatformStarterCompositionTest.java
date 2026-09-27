package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Static D3.8 contract for the consumer-facing BOM and starter dependency boundaries. */
class PlatformStarterCompositionTest {

    private static final Pattern DEPENDENCY_MANAGEMENT =
        Pattern.compile("<dependencyManagement>.*?</dependencyManagement>", Pattern.DOTALL);
    private static final Pattern DEPENDENCY_BLOCK =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern COORDINATES =
        Pattern.compile("<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>");

    @Test
    void backendStarterIsOnlyTheBackendPlatformFacade() {
        assertThat(directDependencies(Path.of("platform-backend-starter/pom.xml")))
            .containsExactly("org.ipro:platform-spring-boot-autoconfigure");
        assertThat(read(Path.of("platform-backend-starter/pom.xml")))
            .contains("<artifactId>platform-bom</artifactId>", "<scope>import</scope>")
            .doesNotContain("vaadin-spring-boot-starter", "ureport", "dynamicreports", "reportui");
    }

    @Test
    void vaadinStarterIncludesTheUiRuntimeAndNoReportStack() {
        assertThat(directDependencies(Path.of("platform-vaadin-starter/pom.xml")))
            .containsExactlyInAnyOrder(
                "org.ipro:platform-vaadin",
                "com.vaadin:vaadin-spring-boot-starter");
        assertThat(read(Path.of("platform-vaadin-starter/pom.xml")))
            .contains("<artifactId>platform-bom</artifactId>", "<scope>import</scope>")
            .doesNotContain("ureport", "dynamicreports", "reportui", "jasperreports");
    }

    @Test
    void bomManagesThePlatformAndTheSharedFilterGridVersionFamily() {
        Set<String> managed = dependencyCoordinates(Path.of("platform-bom/pom.xml"));

        assertThat(managed).contains(
            "org.ipro:platform-identity-api",
            "org.ipro:platform-crud-api",
            "org.ipro:platform-contracts",
            "org.ipro:platform-events",
            "org.ipro:platform-persistence",
            "org.ipro:platform-metadata",
            "org.ipro:platform-numbering",
            "org.ipro:platform-settings",
            "org.ipro:platform-telemetry",
            "org.ipro:platform-rls",
            "org.ipro:platform-core",
            "org.ipro:platform-spring-boot-autoconfigure",
            "org.ipro:platform-vaadin",
            "org.ipro:platform-backend-starter",
            "org.ipro:platform-vaadin-starter",
            "org.ipro:filtergrid-core",
            "org.ipro:filtergrid-grouping",
            "org.ipro:filtergrid-jpa",
            "org.ipro:filtergrid-inmemory",
            "org.ipro:filtergrid-projection",
            "com.vaadin:vaadin-bom");
        assertThat(managed)
            .noneMatch(PlatformStarterCompositionTest::isReportCoordinate);
        assertThat(read(Path.of("pom.xml")))
            .contains("<vaadin.version>25.1.6</vaadin.version>");
        assertThat(read(Path.of("platform-bom/pom.xml")))
            .contains("<vaadin.version>25.1.6</vaadin.version>");
    }

    @Test
    void applicationUsesStartersButKeepsReportAndAppOnlyFilterGridDependencies() {
        Set<String> applicationDependencies = directDependencies(Path.of("pom.xml"));

        assertThat(applicationDependencies).contains(
            "org.ipro:platform-vaadin-starter",
            "org.ipro:filtergrid-core",
            "org.ipro:filtergrid-jpa",
            "org.ipro:filtergrid-inmemory",
            "org.ipro:filtergrid-projection",
            "com.bstek.ureport:ureport3-console",
            "net.sourceforge.dynamicreports:dynamicreports-core",
            "org.vaadin.reports:reportui-core");
        assertThat(applicationDependencies)
            .noneMatch(coordinate -> coordinate.startsWith("org.ipro:platform-")
                && !coordinate.equals("org.ipro:platform-vaadin-starter"));
        assertThat(applicationDependencies)
            .doesNotContain("com.vaadin:vaadin-spring-boot-starter");
        assertThat(applicationDependencies)
            .doesNotContain("org.ipro:filtergrid-grouping");
        assertThat(read(Path.of("pom.xml")))
            .doesNotContain("<filtergrid.version>", "<version>${filtergrid.version}</version>");
        assertThat(read(Path.of("src/main/resources/META-INF/spring/"
            + "org.springframework.boot.autoconfigure.AutoConfiguration.imports")))
            .contains("org.ipro.jr.config.JrPersistenceAutoConfiguration");
    }

    private static Set<String> directDependencies(Path pom) {
        String text = DEPENDENCY_MANAGEMENT.matcher(read(pom)).replaceAll(" ");
        return dependencyCoordinates(text);
    }

    private static Set<String> dependencyCoordinates(Path pom) {
        return dependencyCoordinates(read(pom));
    }

    private static Set<String> dependencyCoordinates(String text) {
        Set<String> coordinates = new TreeSet<>();
        Matcher dependencies = DEPENDENCY_BLOCK.matcher(text);
        while (dependencies.find()) {
            Matcher coordinate = COORDINATES.matcher(dependencies.group(1));
            if (coordinate.find()) {
                coordinates.add(coordinate.group(1) + ":" + coordinate.group(2));
            }
        }
        return coordinates;
    }

    private static boolean isReportCoordinate(String coordinate) {
        String normalized = coordinate.toLowerCase(Locale.ROOT);
        return normalized.contains("ureport") || normalized.contains("dynamicreports")
            || normalized.contains("reportui") || normalized.contains("jasperreports");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать " + path, e);
        }
    }
}
