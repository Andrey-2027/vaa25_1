package org.ip.views;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import org.ip.navigation.NavigationUiSnapshot;
import org.ip.views.admin.EntityExplorerAccess;
import org.ip.views.admin.EntityExplorerNavigation;
import org.ip.views.admin.EntityExplorerWorkspace;
import org.ip.views.preferences.UserPreferences;
import org.ip.views.preferences.UserPreferencesStore;
import org.ip.views.query.QueryWorkbenchAccess;
import org.ip.views.workspace.Workspace;
import org.ip.views.workspace.WorkspaceManager;
import org.ipro.form.host.FormRouteUrlBridge;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.FormRouteOpener;
import org.ipro.vaadin.search.GlobalSearchHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** КИ-7: ящик — разделы сверху без вложенных пунктов, служебный блок внизу. */
class MainLayoutDrawerTest {

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void administratorSeesSectionsWithoutNestedItemsAndTheServiceBlockBelow() {
        var layout = layoutFor("ROLE_ADMIN");

        var sections = navs(layout, "drawer-sections").getFirst();
        assertThat(sections.getItems()).extracting(SideNavItem::getLabel)
            .containsExactly("Главная", "Справочники", "Производство");
        assertThat(sections.getItems()).allSatisfy(item -> assertThat(item.getItems()).isEmpty());

        var service = navs(layout, "drawer-service").getFirst();
        assertThat(service.getItems()).extracting(SideNavItem::getLabel)
            .containsExactly("Конструктор запросов", "Диагностика", "Администрирование",
                "Структура сущностей", "Предметные области");

        var column = components(layout).filter(Div.class::isInstance).map(Div.class::cast)
            .filter(div -> div.hasClassName("drawer-column")).findFirst().orElseThrow();
        assertThat(column.getChildren().toList()).containsExactly(sections, service);
    }

    @Test
    void userWithoutAdminRoleDoesNotSeeTheAdministrativePartOfTheServiceBlock() {
        var layout = layoutFor("ROLE_USER");

        var service = navs(layout, "drawer-service").getFirst();
        assertThat(service.getItems()).extracting(SideNavItem::getLabel)
            .containsExactly("Конструктор запросов");
        assertThat(allLabels(layout)).doesNotContain("Диагностика", "Администрирование",
            "Структура сущностей", "Предметные области");
    }

    @Test
    void legacyDirectoriesEntryIsGone() {
        assertThat(allLabels(layoutFor("ROLE_ADMIN"))).noneMatch(label -> label.contains("legacy"));
    }

    private static MainLayout layoutFor(String role) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "drawer-test", "", List.of(new SimpleGrantedAuthority(role))));
        var factory = mock(AutowireCapableBeanFactory.class);
        var main = new MainView(mock(ObjectProvider.class));
        when(factory.createBean(MainView.class)).thenReturn(main);
        var workspace = new Workspace(new WorkspaceManager(factory));
        var snapshot = mock(NavigationUiSnapshot.class);
        doAnswer(call -> {
            SideNav menu = call.getArgument(0);
            menu.addItem(new SideNavItem("Справочники"), new SideNavItem("Производство"));
            return null;
        }).when(snapshot).appendMenu(any(), any());
        var store = mock(UserPreferencesStore.class);
        when(store.get()).thenReturn(UserPreferences.DEFAULTS);
        return new MainLayout(workspace, store,
            new GlobalSearchHeader(null, null), mock(FormRouteOpener.class), mock(FormRouteUrlBridge.class),
            new EntityExplorerAccess(), mock(FormRouteCatalog.class), mock(EntityExplorerNavigation.class),
            mock(EntityExplorerWorkspace.class), snapshot, new QueryWorkbenchAccess());
    }

    private static List<SideNav> navs(Component root, String className) {
        return components(root).filter(SideNav.class::isInstance).map(SideNav.class::cast)
            .filter(nav -> nav.hasClassName(className)).toList();
    }

    private static List<String> allLabels(Component root) {
        return components(root).filter(SideNavItem.class::isInstance).map(SideNavItem.class::cast)
            .map(SideNavItem::getLabel).toList();
    }

    private static Stream<Component> components(Component component) {
        return component.getChildren().flatMap(child -> Stream.concat(Stream.of(child), components(child)));
    }
}
