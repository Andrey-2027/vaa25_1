package org.ipro.navigation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Only an isolated local DB supplied by test-navigation-postgresql.ps1 is permitted. */
@EnabledIfEnvironmentVariable(named = "NAVIGATION_TEST_JDBC_URL",
    matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/navigation_ki2_[a-z0-9_]+")
class NavigationPostgresqlTest {
    @Test
    void additiveDdlValidatesAndProfileAndAssignmentSurviveContextRestart() throws Exception {
        String url = System.getenv("NAVIGATION_TEST_JDBC_URL");
        String username = System.getenv("NAVIGATION_TEST_JDBC_USERNAME");
        String password = System.getenv("NAVIGATION_TEST_JDBC_PASSWORD");
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement();
             var input = getClass().getResourceAsStream("/db/navigation-postgresql.sql")) {
            assertThat(input).isNotNull();
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
        var users = new NavigationPersistenceTest.Users();
        var function = new NavigationFunction("list:first", Object.class, NavigationFunction.Kind.LIST,
            "First", "", 0, "child");
        var runner = NavigationPersistenceTest.database(url, "validate")
            .withPropertyValues("spring.datasource.username=" + username, "spring.datasource.password=" + password)
            .withBean(NavigationAdministrationAccess.class, () -> () -> true)
            .withBean(NavigationUserDirectory.class, () -> users)
            .withBean(NavigationFunctions.class, () -> new NavigationFunctions() {
                public java.util.List<NavigationFunction> all() { return java.util.List.of(function); }
                public java.util.Optional<NavigationFunction> find(String key) {
                    return function.key().equals(key) ? java.util.Optional.of(function) : java.util.Optional.empty();
                }
            });
        var id = new AtomicLong();
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var bootstrap = context.getBean(org.ipro.navigation.internal.NavigationStructureBootstrap.class);
            bootstrap.initialize();
            var structure = context.getBean(NavigationStructureService.class);
            var initial = structure.structure();
            structure.save(initial.version(), new NavigationStructure(initial.version(), initial.seededAt(),
                initial.sections(), java.util.List.of(
                    new NavigationStructure.Entry("first", "root", function.key(), null, 0))));
            var service = context.getBean(NavigationAdministrationService.class);
            var profile = service.createProfile("PostgreSQL profile");
            var saved = service.saveProfile(profile.id(), profile.version(), Map.of("entry:first", NavigationVisibility.HIDE));
            service.assignUser("101", saved.id());
            id.set(saved.id());
        });
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(org.ipro.navigation.internal.NavigationStructureBootstrap.class).initialize();
            assertThat(context.getBean(NavigationStructureService.class).structure().entries())
                .singleElement().satisfies(entry -> assertThat(entry.sectionId()).isEqualTo("root"));
            var reader = context.getBean(NavigationProfileReader.class);
            var restored = reader.currentProfile().orElseThrow();
            assertThat(restored.id()).isEqualTo(id.get());
            assertThat(restored.overrides()).containsExactlyEntriesOf(Map.of("entry:first", NavigationVisibility.HIDE));
            var service = context.getBean(NavigationAdministrationService.class);
            assertThatThrownBy(() -> service.saveProfile(restored.id(), restored.version() - 1, Map.of()))
                .isInstanceOf(NavigationConflictException.class);
            assertThat(service.resetProfile(restored.id(), restored.version()).overrides()).isEmpty();
            assertThat(service.assignmentForUser("101")).contains(restored.id());
        });
    }
}
