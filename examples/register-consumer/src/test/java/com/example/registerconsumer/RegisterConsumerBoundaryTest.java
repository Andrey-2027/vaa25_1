package com.example.registerconsumer;

import org.ipro.crud.StandardCatalogEntity;
import org.ipro.data.EntityDataAccess;
import org.ipro.register.RegisterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A2.1 boundary acceptance: the platform comes from installed JARs, and the backend dependency
 * closure contains no Vaadin, UI, HTTP or report slice. The consumer does not link the root
 * application sources either.
 */
@SpringBootTest(properties = "consumer.demo-password=register-consumer-test-only")
class RegisterConsumerBoundaryTest {

    @Test
    void platformTypesAreLoadedFromJars() {
        for (Class<?> platformType : List.of(RegisterRegistry.class, EntityDataAccess.class,
                StandardCatalogEntity.class, org.ipro.register.RegisterMetadata.class)) {
            String location = platformType.getProtectionDomain().getCodeSource().getLocation().toString();
            assertThat(location)
                .as(platformType.getName() + " must come from an installed JAR, not project sources")
                .endsWith(".jar");
        }
    }

    @Test
    void rootApplicationUiAndHttpSlicesAreAbsent() {
        assertThatThrownBy(() -> Class.forName("org.ip.Application"))
            .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("org.ip.model.Nomenclature"))
            .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.vaadin.flow.component.UI"))
            .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("org.ipro.rest.web.RestWebAutoConfiguration"))
            .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("org.apache.catalina.startup.Tomcat"))
            .as("no embedded servlet container in a backend-only consumer")
            .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void runtimeDependencyTreeCarriesNoUiHttpOrReportArtifacts() throws Exception {
        Path tree = Path.of("target", "register-consumer-runtime-dependency-tree.json");
        assertThat(tree).as("the dependency tree is produced before tests").exists();
        String json = Files.readString(tree).toLowerCase(Locale.ROOT);

        assertThat(json).contains("platform-backend-starter");
        assertThat(json).contains("platform-register");
        assertThat(json).contains("platform-spring-boot-autoconfigure");

        for (String forbidden : List.of("\"vaadin", "platform-vaadin", "platform-rest",
                "platform-report", "platform-query-vaadin", "platform-navigation",
                "spring-boot-starter-web", "spring-webmvc", "tomcat-embed-core",
                "tomcat-embed-websocket", "spring-boot-starter-tomcat")) {
            assertThat(json)
                .as("backend closure must not contain %s", forbidden)
                .doesNotContain(forbidden);
        }
    }
}
