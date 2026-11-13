package org.ipro.navigation;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Owner-side gates run when this artifact is built without the application or a UI runtime. */
class NavigationModuleCompositionTest {
    private static final Path SOURCES = Path.of("src/main/java/org/ipro/navigation");
    private static final Path ROLES = Path.of("src/main/resources/META-INF/navigation-surface.txt");

    @Test
    void everyProductionTypeIncludingNestedTypesHasExactlyOneReviewedRole() throws Exception {
        Map<String, String> roles = new HashMap<>();
        for (String line : Files.readAllLines(ROLES)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] columns = line.split("\\s+");
            assertThat(columns).hasSize(2);
            assertThat(columns[0]).isIn("APP_API", "APP_SPI", "MODULE_API", "INTERNAL");
            assertThat(roles.put(columns[1], columns[0])).as("duplicate role: %s", columns[1]).isNull();
        }
        Set<String> actual = new HashSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = "org.ipro.navigation." + SOURCES.relativize(file).toString()
                    .replace('\\', '.').replace('/', '.').replace(".java", "");
                collect(Class.forName(name), actual);
            }
        }
        assertThat(roles.keySet()).containsExactlyInAnyOrderElementsOf(actual);
        assertThat(roles.get(NavigationEntityKeys.class.getName())).isEqualTo("APP_SPI");
        assertThat(roles.get(NavigationCatalogProvider.class.getName())).isEqualTo("APP_API");
        try (var published = getClass().getResourceAsStream("/META-INF/navigation-surface.txt")) {
            assertThat(published).isNotNull();
            assertThat(new String(published.readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(Files.readString(ROLES));
        }
    }

    private static void collect(Class<?> type, Set<String> actual) {
        actual.add(type.getName());
        for (Class<?> nested : type.getDeclaredClasses()) collect(nested, actual);
    }

    @Test
    void onlyActualBackendDependencyIsDeclaredAndUiAndApplicationAreAbsent() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var dependencies = document.getElementsByTagName("dependency");
        Set<String> compile = new HashSet<>();
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dependency = (Element) dependencies.item(i);
            var scope = dependency.getElementsByTagName("scope");
            if (scope.getLength() == 0 || scope.item(0).getTextContent().equals("compile")) {
                compile.add(dependency.getElementsByTagName("groupId").item(0).getTextContent()
                    + ":" + dependency.getElementsByTagName("artifactId").item(0).getTextContent());
            }
        }
        assertThat(compile).containsExactlyInAnyOrder("org.ipro:platform-core", "org.ipro:platform-persistence",
            "jakarta.persistence:jakarta.persistence-api", "org.springframework.data:spring-data-jpa",
            "org.springframework:spring-tx", "org.springframework.security:spring-security-core",
            "org.springframework.boot:spring-boot-autoconfigure", "org.springframework.boot:spring-boot-persistence");
        for (String forbidden : Set.of("com.vaadin.flow.component.Component", "org.ip.model.User",
            "org.ipro.form.link.FormRouteCatalog", "org.ipro.reportstudio.query.ReportQuery")) {
            assertThatThrownBy(() -> Class.forName(forbidden)).isInstanceOf(ClassNotFoundException.class);
        }
    }
}
