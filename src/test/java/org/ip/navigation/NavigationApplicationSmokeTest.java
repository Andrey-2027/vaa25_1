package org.ip.navigation;

import org.ip.Application;
import org.ipro.navigation.NavigationAdministrationService;
import org.ipro.navigation.NavigationProfileReader;
import org.ipro.navigation.NavigationVisibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real application wiring, including the RLS flush guard, with navigation explicitly enabled. */
@SpringBootTest(classes = Application.class, properties = {
    "platform.navigation.enabled=true", "app.demo-data.enabled=false", "rls.write-boundary.enforced=true",
    "spring.datasource.url=jdbc:h2:mem:navigation-ki2-app;DB_CLOSE_DELAY=-1",
    "vaadin.allowed-packages=org.ip.views.login,com.vaadin", "vaadin.productionMode=true"})
@ContextConfiguration(initializers = NavigationSmokeIsolation.class)
class NavigationApplicationSmokeTest {
    @Autowired NavigationAdministrationService administration;
    @Autowired NavigationProfileReader reader;
    @Autowired org.springframework.context.ConfigurableApplicationContext context;
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void ownerOperationsWorkInsideActualApplicationAndSecurityDoesNotRequireAnAopProxy() {
        assertThat(context.getBeanFactory().getBeanDefinition("navigationUiSnapshot").getScope()).isEqualTo("vaadin-ui");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-test-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var created = administration.createProfile("KI-2 app smoke");
        var saved = administration.saveProfile(created.id(), created.version(), Map.of("item:unit-of-measurement", NavigationVisibility.HIDE));
        assertThat(administration.profile(saved.id())).isEqualTo(saved);
        assertThat(administration.resetProfile(saved.id(), saved.version()).overrides()).isEmpty();
        // An authenticated name without a persisted User.id must not silently get a default profile.
        assertThatThrownBy(reader::currentProfile).isInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-test-user", "", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertThatThrownBy(() -> administration.createProfile("Forbidden")).isInstanceOf(AccessDeniedException.class);
    }
}
