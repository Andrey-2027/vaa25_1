package org.ip.views.admin;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.ip.service.AccessGrantAdminService;
import org.ipro.navigation.NavigationAdministrationService;
import org.ipro.navigation.NavigationResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NavigationAdminWiringTest {
    private final ObjectProvider<NavigationProfilesAdminTab> tabs = mock(ObjectProvider.class);
    private final NavigationAdministrationService service = mock(NavigationAdministrationService.class);

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void adminSeesTheTabAndReattachKeepsTheSameEditor() {
        login("ROLE_ADMIN");
        var editor = new NavigationProfilesAdminTab(service, () -> true, NavigationProfileEditorTest.catalog(), new NavigationResolver());
        when(tabs.getObject()).thenReturn(editor);
        var view = adminView(true);
        view.onAttach(new AttachEvent(view, false));
        Tabs tabBar = children(view).filter(Tabs.class::isInstance).map(Tabs.class::cast).findFirst().orElseThrow();
        assertThat(tabBar.getComponentCount()).isEqualTo(4);
        verifyNoInteractions(service);
        tabBar.setSelectedTab((Tab) tabBar.getComponentAt(3));
        assertThat(editor.isVisible()).isTrue();
        verify(service).listProfiles();
        view.onAttach(new AttachEvent(view, false));
        verify(tabs, times(1)).getObject();
        assertThat(children(view).filter(NavigationProfilesAdminTab.class::isInstance)).containsExactly(editor);
        tabBar.setSelectedIndex(0);
        assertThat(editor.isVisible()).isFalse();
        tabBar.setSelectedIndex(3);
        verifyNoMoreInteractions(service);
    }

    @Test void disabledModeKeepsTheThreeExistingTabsAndDoesNotRequestTheEditor() {
        login("ROLE_ADMIN");
        var view = adminView(false);
        view.onAttach(new AttachEvent(view, false));
        var tabBar = children(view).filter(Tabs.class::isInstance).map(Tabs.class::cast).findFirst().orElseThrow();
        assertThat(tabBar.getComponentCount()).isEqualTo(3);
        verifyNoInteractions(tabs, service);
    }

    @Test void nonAdministratorCannotCreateOrOpenTheEditor() {
        login("ROLE_USER");
        var view = adminView(true);
        view.onAttach(new AttachEvent(view, false));
        assertThat(children(view).filter(Tabs.class::isInstance)).isEmpty();
        verifyNoInteractions(tabs, service);
    }

    private AdminView adminView(boolean enabled) {
        var factory = mock(EntityManagerFactory.class);
        var metamodel = mock(Metamodel.class);
        when(factory.getMetamodel()).thenReturn(metamodel);
        when(metamodel.getEntities()).thenReturn(Set.of());
        return new AdminView(mock(AccessGrantAdminService.class), new SettingsAdminTab(null, null, null, null, null),
            new NumberingAdminTab(null, null, null, null, null), factory, List.of(), tabs, enabled);
    }
    private static void login(String role) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-admin-test", "", List.of(new SimpleGrantedAuthority(role))));
    }
    private static Stream<Component> children(Component component) {
        return component.getChildren().flatMap(child -> Stream.concat(Stream.of(child), children(child)));
    }
}
