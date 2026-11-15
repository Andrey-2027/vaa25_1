package org.ip.views.admin.menu;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.VaadinSessionState;
import org.ip.Application;
import org.ip.navigation.NavigationSmokeIsolation;
import org.ipro.navigation.NavigationMenuService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The editor wired into the real application: real service, real menu of the application, H2. */
@SpringBootTest(classes = Application.class, properties = {
    "app.demo-data.enabled=false", "rls.write-boundary.enforced=true",
    "spring.datasource.url=jdbc:h2:mem:menu-editor-sm3;DB_CLOSE_DELAY=-1",
    "vaadin.allowed-packages=org.ip.views.login,com.vaadin", "vaadin.productionMode=true"})
@ContextConfiguration(initializers = NavigationSmokeIsolation.class)
class MenuEditorApplicationSmokeTest {
    @Autowired ObjectProvider<MenuEditorAdminTab> tabs;
    @Autowired NavigationMenuService service;

    @BeforeEach
    void prepare() {
        var ui = new UI();
        var session = mock(VaadinSession.class);
        when(session.hasLock()).thenReturn(true);
        when(session.getState()).thenReturn(VaadinSessionState.OPEN);
        ui.getInternals().setSession(session);
        VaadinSession.setCurrent(session);
        UI.setCurrent(ui);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "menu-editor-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        UI.setCurrent(null);
        VaadinSession.setCurrent(null);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void exportedJsonImportsIntoAnEmptyMenuWithTheSamePlacementKeys() {
        var main = service.menus().stream().filter(org.ipro.navigation.NavigationMenu::main).findFirst().orElseThrow();
        var original = service.structure(main.id());
        var file = MenuJson.decode(MenuJson.encode(main.name(), original));
        var known = service.functions().stream().map(row -> row.function().key())
            .collect(java.util.stream.Collectors.toSet());
        var preview = MenuJson.preview(file, null, known);
        var created = service.importMenu("Перенесённое меню", preview.structure());
        var imported = service.structure(created.id());
        assertThat(imported.sections()).containsExactlyInAnyOrderElementsOf(original.sections());
        assertThat(imported.entries()).containsExactlyInAnyOrderElementsOf(original.entries());
        assertThat(service.assignees(created.id())).isEmpty();
    }

    @Test
    void theEditorOpensTheMainMenuOfTheApplicationAndSavesAnEditThroughTheRealService() {
        var tab = tabs.getObject();
        tab.refresh();

        assertThat(tab.locked()).isFalse();
        assertThat(tab.currentMenu().main()).isTrue();
        assertThat(tab.isDirty()).isFalse();
        assertThat(tab.draft().rootSections()).extracting(section -> section.id())
            .contains("directories", "production");
        assertThat(tab.palette().roots()).extracting(MenuPaletteModel.Node::title).contains("Справочники", "Производство");
        var directories = tab.palette().findArea("directories").orElseThrow();
        assertThat(directories.hasLists()).isTrue();

        // A second placement of a list in another section, saved and read back.
        var list = directories.lists().getFirst().function();
        tab.select(new MenuEditorAdminTab.MenuRow(true, "production"));
        tab.addFunctionHere(list);
        assertThat(tab.isDirty()).isTrue();
        assertThat(tab.doSave()).isTrue();
        assertThat(tab.isDirty()).isFalse();
        assertThat(service.structure(tab.currentMenu().id()).entries())
            .filteredOn(entry -> entry.functionKey().equals(list.key())).hasSize(2);
        assertThat(tab.palette().count(list)).isEqualTo(2);

        // The same menu opens again without a restart and a copy is independent of it.
        var copy = service.copy(tab.currentMenu().id(), "Меню технолога");
        tab.refresh();
        tab.useMenu(copy.id() == tab.currentMenu().id() ? tab.currentMenu() : service.menus().stream()
            .filter(menu -> menu.id() == copy.id()).findFirst().orElseThrow());
        assertThat(tab.currentMenu().name()).isEqualTo("Меню технолога");
        assertThat(tab.stripVisible()).isTrue();
        tab.deleteCurrent();
        assertThat(tab.currentMenu().main()).isTrue();
    }
}
