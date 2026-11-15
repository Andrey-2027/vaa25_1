package org.ip.views.home;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.VaadinSessionState;
import org.ip.Application;
import org.ip.navigation.NavigationSmokeIsolation;
import org.ipro.form.coordinator.FormOpenEvent;
import org.ipro.form.coordinator.FormOpenObserver;
import org.ipro.navigation.NavigationFunctionsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** «Главное» в настоящем приложении: бины, хранение в user_form_settings, название группы по настоящему меню. */
@SpringBootTest(classes = Application.class, properties = {
    "app.demo-data.enabled=false", "rls.write-boundary.enforced=true",
    "spring.datasource.url=jdbc:h2:mem:home-ki13;DB_CLOSE_DELAY=-1",
    "vaadin.allowed-packages=org.ip.views.login,com.vaadin", "vaadin.productionMode=true"})
@ContextConfiguration(initializers = NavigationSmokeIsolation.class)
class HomeApplicationSmokeTest {
    @Autowired ObjectProvider<HomeView> views;
    @Autowired HomeService home;
    @Autowired HomeGroupResolver groups;
    @Autowired HomeRecentRecorder recorder;
    @Autowired ObjectProvider<FormOpenObserver> observers;
    @Autowired NavigationFunctionsProvider functions;

    @BeforeEach
    void prepare() {
        var ui = new UI();
        var session = mock(VaadinSession.class);
        when(session.hasLock()).thenReturn(true);
        when(session.getState()).thenReturn(VaadinSessionState.OPEN);
        when(session.getLockInstance()).thenReturn(new java.util.concurrent.locks.ReentrantLock());
        // UI-scoped beans (the form navigator, the workspace) keep their state in session attributes.
        java.util.Map<Class<?>, Object> attributes = new java.util.HashMap<>();
        when(session.getAttribute(org.mockito.ArgumentMatchers.any(Class.class)))
            .thenAnswer(call -> attributes.get(call.getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> { attributes.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(session).setAttribute(org.mockito.ArgumentMatchers.any(Class.class), org.mockito.ArgumentMatchers.any());
        ui.getInternals().setSession(session);
        VaadinSession.setCurrent(session);
        UI.setCurrent(ui);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "home-smoke-" + java.util.UUID.randomUUID(), "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        UI.setCurrent(null);
        VaadinSession.setCurrent(null);
    }

    @Test
    void theRecorderIsRegisteredAsAFormOpenObserver() {
        assertThat(observers.stream()).anyMatch(HomeRecentRecorder.class::isInstance);
    }

    @Test
    void theSettingsRoundTripThroughTheDatabaseAndAreKeptPerUser() {
        assertThat(home.layout().isEmpty()).isTrue();
        var pin = HomePin.function("list:prd-spec");
        var layout = new HomeLayout();
        layout.pin(pin, "Нормативы");
        home.saveLayout(layout);
        home.record(pin, Instant.parse("2026-11-15T10:00:00Z"));
        home.saveUi(new HomeJson.UiState(true));

        assertThat(home.layout().isPinned(pin)).isTrue();
        assertThat(home.recent()).extracting(HomeJson.Recent::pin).containsExactly(pin);
        assertThat(home.ui().recentExpanded()).isTrue();

        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "another-home-user", "", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertThat(home.layout().isEmpty()).as("the layout belongs to the user").isTrue();
        assertThat(home.ui().recentExpanded()).isFalse();
    }

    @Test
    void theGroupIsNamedAfterTheSectionOfTheRealMainMenu() {
        assertThat(functions.find("list:prd-spec")).isPresent();
        assertThat(groups.groupTitle(HomePin.function("list:prd-spec"))).isEqualTo("Производство");
        assertThat(groups.groupTitle(HomePin.function("list:does-not-exist"))).isEqualTo(HomeGroupResolver.OTHER);
    }

    @Test
    void anOpenedListIsRecordedAndTheHomePageShowsWhatIsPinnedAndHidesWhatWasRemoved() {
        recorder.opened(new FormOpenEvent(FormOpenEvent.Kind.LIST, org.ip.model.PrdSpec.class, null, null, true));
        assertThat(home.recent()).extracting(entry -> entry.pin().key()).contains("list:prd-spec");

        var view = views.getObject();
        assertThat(view.layout().isEmpty()).isTrue();

        var layout = new HomeLayout();
        layout.pin(HomePin.function("list:prd-spec"), groups.groupTitle(HomePin.function("list:prd-spec")));
        layout.pin(HomePin.function("list:removed-from-code"), "Прочее");
        home.saveLayout(layout);
        view.refresh();

        assertThat(view.layout().itemCount()).isEqualTo(2);
        assertThat(view.page().hidden()).as("a function that no longer exists is hidden, not lost")
            .extracting(item -> item.pin().key()).containsExactly("list:removed-from-code");
    }
}
