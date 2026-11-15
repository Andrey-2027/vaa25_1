package org.ipro.navigation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses the isolated database created by test-navigation-postgresql.ps1. */
@EnabledIfEnvironmentVariable(named = "NAVIGATION_TEST_JDBC_URL",
    matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/navigation_ki2_[a-z0-9_]+")
class NavigationPostgresqlTest {
    @Test
    void schemaValidatesAndAssignmentsAndCopiesSurviveRestart() throws Exception {
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
        var runner = NavigationPersistenceTest.database(url, "validate", users, new AtomicBoolean(true))
            .withPropertyValues("spring.datasource.username=" + username, "spring.datasource.password=" + password);
        var id = new AtomicLong();
        runner.run(context -> {
            var service = NavigationPersistenceTest.initialize(context);
            var main = service.menus().getFirst();
            var copy = service.copy(main.id(), "PostgreSQL copy");
            id.set(copy.id());
            service.assign(copy.id(), Set.of("101"), Set.of());
            assertThat(service.structure(copy.id()).entries()).isEqualTo(service.structure(main.id()).entries());
        });
        runner.run(context -> {
            var service = NavigationPersistenceTest.initialize(context);
            var reader = context.getBean(NavigationMenuReader.class);
            assertThat(reader.currentMenu().menuId()).isEqualTo(id.get());
            var restored = service.menus().stream().filter(menu -> menu.id() == id.get()).findFirst().orElseThrow();
            service.delete(restored.id(), restored.version());
            assertThat(reader.currentMenu().menuId()).isEqualTo(service.menus().getFirst().id());
        });
    }
}
