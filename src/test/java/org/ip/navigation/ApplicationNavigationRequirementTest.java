package org.ip.navigation;

import org.ip.Application;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Д1: the module stays optional for the platform, the application cannot run without it. */
class ApplicationNavigationRequirementTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ApplicationNavigationRequirement.class);

    @Test
    void disabledFlagIsAClearStartupError() {
        for (String value : new String[] {"platform.navigation.enabled=false", "unrelated=1"}) {
            runner.withPropertyValues(value).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Навигация обязательна для приложения");
            });
        }
    }

    @Test
    void enabledFlagStartsNormally() {
        runner.withPropertyValues("platform.navigation.enabled=true").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theApplicationItselfRefusesToStartWithoutNavigation() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(Application.class)
            .web(WebApplicationType.NONE).initializers(new NavigationSmokeIsolation())
            .properties("app.demo-data.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:navigation-required;DB_CLOSE_DELAY=-1",
                "vaadin.allowed-packages=org.ip.views.login,com.vaadin", "vaadin.productionMode=true")
            // Command line, not a default property: application.properties must not win.
            .run("--platform.navigation.enabled=false"))
            .hasStackTraceContaining("Навигация обязательна для приложения");
    }
}
