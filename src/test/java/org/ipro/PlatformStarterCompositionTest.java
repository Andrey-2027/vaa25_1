package org.ipro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Static D3.8 contract for the consumer-facing BOM and starter dependency boundaries. */
class PlatformStarterCompositionTest {

    private static final Path RESOLVED_RUNTIME_TREE =
        Path.of(System.getProperty("platform.runtime.dependency-tree",
            "target/application-runtime-dependency-tree.json"));

    private static final Pattern DEPENDENCY_MANAGEMENT =
        Pattern.compile("<dependencyManagement>.*?</dependencyManagement>", Pattern.DOTALL);
    private static final Pattern DEPENDENCY_BLOCK =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern COORDINATES =
        Pattern.compile("<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>");

    /**
     * D3.8 + D-R S5(a), ADR-0024 §2.2: backend-поставка — это фасад платформы и движок регистра.
     *
     * <p>Регистр объявлен здесь прямым ребром сознательно: объявив регистр, потребитель без UI
     * обязан получить механизм, а не строку сборки, которую можно забыть. Обратная сторона тоже
     * проверяется: ни UI-модуля, ни REST в backend-поставке нет — иначе «фасад» перестал бы быть
     * отдельной от UI опцией (общий замысел фасада — допускать потребителя без интерфейса и без
     * REST-среза). Разрешённое замыкание стартера проверяет его собственный
     * `BackendStarterResolvedDependenciesTest`: там дерево, а не декларация.</p>
     */
    @Test
    void backendStarterCarriesTheBackendPlatformAndTheRegisterEngineWithoutUiOrRest() {
        assertThat(directDependencies(Path.of("platform-backend-starter/pom.xml")))
            .containsExactlyInAnyOrder(
                "org.ipro:platform-spring-boot-autoconfigure",
                "org.ipro:platform-register");
        assertThat(read(Path.of("platform-backend-starter/pom.xml")))
            .contains("<artifactId>platform-bom</artifactId>", "<scope>import</scope>")
            .doesNotContain("vaadin-spring-boot-starter", "platform-register-vaadin",
                "platform-rest", "ureport", "dynamicreports", "reportui");
    }

    @Test
    void vaadinStarterIncludesTheUiRuntimeAndNoReportStack() {
        assertThat(directDependencies(Path.of("platform-vaadin-starter/pom.xml")))
            .containsExactlyInAnyOrder(
                "org.ipro:platform-vaadin",
                "org.ipro:platform-register-vaadin",
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
            "org.ipro:platform-rest-web",
            "org.ipro:platform-vaadin",
            "org.ipro:platform-register-vaadin",
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
            "org.ipro:platform-rest-web",
            "org.ipro:filtergrid-core",
            "org.ipro:filtergrid-jpa",
            "org.ipro:filtergrid-inmemory",
            "org.ipro:filtergrid-projection",
            "net.sourceforge.dynamicreports:dynamicreports-core",
            "org.vaadin.reports:reportui-core");
        assertThat(applicationDependencies)
            .noneMatch(coordinate -> coordinate.startsWith("org.ipro:platform-")
                && !coordinate.equals("org.ipro:platform-vaadin-starter")
                && !coordinate.equals("org.ipro:platform-rest")
                && !coordinate.equals("org.ipro:platform-rest-web")
                // Explicit application modules from the report/query extraction; none enter the starters.
                && !coordinate.equals("org.ipro:platform-report-core")
                && !coordinate.equals("org.ipro:platform-report")
                && !coordinate.equals("org.ipro:platform-jr")
                && !coordinate.equals("org.ipro:platform-unidr")
                && !coordinate.equals("org.ipro:platform-query")
                && !coordinate.equals("org.ipro:platform-query-vaadin")
                // KI-1: приложение подключает проекцию навигации напрямую — она ещё не в стартере.
                && !coordinate.equals("org.ipro:platform-navigation")
                // M2.2.2: отчётный UI — app-owned add-on, а не часть starter-а.
                && !coordinate.equals("org.ipro:platform-report-vaadin"));
        assertThat(applicationDependencies)
            .as("UReport удалён из поставки (U1.3): зависимость не должна вернуться ни прямо, ни транзитивно")
            .doesNotContain("com.bstek.ureport:ureport3-console");
        assertThat(read(Path.of("pom.xml")))
            .doesNotContain("com.bstek.ureport", "ureport.version", "ureport3");
        assertThat(applicationDependencies)
            .doesNotContain("com.vaadin:vaadin-spring-boot-starter");
        assertThat(applicationDependencies)
            .doesNotContain("org.ipro:filtergrid-grouping");
        assertThat(read(Path.of("pom.xml")))
            .doesNotContain("<filtergrid.version>", "<version>${filtergrid.version}</version>");
        // M2.1/M2.2.2: приложение не регистрирует platform-авто-конфигурации; M2.2.2
        // удалил последний app-owned imports-файл вместе с ReportStudioAutoConfiguration.
        Path appImports = Path.of("src/main/resources/META-INF/spring/"
            + "org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(Files.exists(appImports) ? read(appImports) : "")
            .doesNotContain("org.ipro.jr.config.JrPersistenceAutoConfiguration");
    }

    @Test
    void resolvedStarterRuntimeClasspathDoesNotPullTheOptionalRestApi() {
        JsonNode root = readResolvedRuntimeTree();
        assertThat(root.path("artifactId").asText()).isEqualTo("Vaa25_1");

        JsonNode vaadinStarter = directChildren(root).stream()
            .filter(node -> "org.ipro".equals(node.path("groupId").asText()))
            .filter(node -> "platform-vaadin-starter".equals(node.path("artifactId").asText()))
            .findFirst()
            .orElse(null);
        assertThat(vaadinStarter)
            .as("проверка должна видеть starter в разрешённом Maven runtime-графе приложения")
            .isNotNull();

        Set<String> starterClosure = new TreeSet<>();
        collectArtifactIds(vaadinStarter, starterClosure);
        // M2.2.2: приложение подключает platform-query-vaadin/platform-report-vaadin напрямую,
        // поэтому Maven поднимает platform-vaadin в корень (nearest winner), и в поддереве
        // starter его уже нет. Проверяется весь runtime-граф приложения, а не только поддерево.
        assertThat(starterClosure)
            .as("starter не тянет optional REST-срез")
            .doesNotContain("platform-rest", "platform-rest-web");

        // Maven places a shared dependency at its nearest winner. With direct platform-rest,
        // core/autoconfigure appear under REST and are omitted from the starter subtree.
        // Check the declared starter path and the resolved application classpath separately.
        // The standalone starter closure is also checked by VaadinStarterResolvedDependenciesTest.
        assertThat(directDependencies(Path.of("platform-vaadin/pom.xml")))
            .contains("org.ipro:platform-spring-boot-autoconfigure");
        Set<String> applicationClosure = new TreeSet<>();
        collectArtifactIds(root, applicationClosure);
        assertThat(applicationClosure).contains("platform-spring-boot-autoconfigure", "platform-vaadin");

        List<String> directRestDependencies = directChildren(root).stream()
            .filter(node -> "org.ipro".equals(node.path("groupId").asText()))
            .filter(node -> node.path("artifactId").asText().startsWith("platform-rest"))
            .map(node -> node.path("groupId").asText() + ":" + node.path("artifactId").asText())
            .toList();
        assertThat(directRestDependencies)
            .as("REST read-срез разрешён приложением как отдельные прямые опции, а не через starter")
            .containsExactlyInAnyOrder("org.ipro:platform-rest", "org.ipro:platform-rest-web");
    }

    private static JsonNode readResolvedRuntimeTree() {
        try {
            return new ObjectMapper().readTree(Files.readString(RESOLVED_RUNTIME_TREE,
                StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать разрешённый Maven runtime-граф "
                + RESOLVED_RUNTIME_TREE, e);
        }
    }

    private static List<JsonNode> directChildren(JsonNode node) {
        List<JsonNode> children = new ArrayList<>();
        JsonNode value = node.path("children");
        if (value.isArray()) value.forEach(children::add);
        return children;
    }

    private static void collectArtifactIds(JsonNode node, Set<String> result) {
        result.add(node.path("artifactId").asText());
        directChildren(node).forEach(child -> collectArtifactIds(child, result));
    }

    private static Set<String> directDependencies(Path pom) {
        String text = DEPENDENCY_MANAGEMENT.matcher(read(pom)).replaceAll(" ");
        Set<String> coordinates = new TreeSet<>();
        Matcher dependencies = DEPENDENCY_BLOCK.matcher(text);
        while (dependencies.find()) {
            String dependency = dependencies.group(1);
            if (dependency.contains("<scope>test</scope>")) continue;
            Matcher coordinate = COORDINATES.matcher(dependency);
            if (coordinate.find()) {
                coordinates.add(coordinate.group(1) + ":" + coordinate.group(2));
            }
        }
        return coordinates;
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
        if (!Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать " + path, e);
        }
    }
}
