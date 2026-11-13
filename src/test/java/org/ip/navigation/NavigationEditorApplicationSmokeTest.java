package org.ip.navigation;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import org.ip.Application;
import org.ip.views.admin.NavigationProfilesAdminTab;
import org.ipro.navigation.NavigationAdministrationService;
import org.ipro.navigation.NavigationProfile;
import org.ipro.navigation.NavigationVisibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Editor buttons and renderer value changes against the real protected service and H2 store. */
@SpringBootTest(classes = Application.class, properties = {
    "app.demo-data.enabled=false", "rls.write-boundary.enforced=true",
    "spring.datasource.url=jdbc:h2:mem:navigation-ki2-app;DB_CLOSE_DELAY=-1",
    "vaadin.allowed-packages=org.ip.views.login,com.vaadin", "vaadin.productionMode=true"})
@ContextConfiguration(initializers = NavigationSmokeIsolation.class)
class NavigationEditorApplicationSmokeTest {
    @Autowired ConfigurableApplicationContext context;
    @Autowired NavigationAdministrationService administration;
    private UI ui;

    @AfterEach void cleanup() {
        UI.setCurrent(null);
        com.vaadin.flow.server.VaadinSession.setCurrent(null);
        ui = null;
        SecurityContextHolder.clearContext();
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void twoProfilesCanBeCreatedAndSavedThroughTheEditorWithoutJavaOrSqlChanges() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-editor-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        ui = new UI();
        var session = org.mockito.Mockito.mock(com.vaadin.flow.server.VaadinSession.class);
        org.mockito.Mockito.when(session.hasLock()).thenReturn(true);
        org.mockito.Mockito.when(session.getState()).thenReturn(com.vaadin.flow.server.VaadinSessionState.OPEN);
        ui.getInternals().setSession(session);
        com.vaadin.flow.server.VaadinSession.setCurrent(session);
        UI.setCurrent(ui);
        var editor = context.getBean(NavigationProfilesAdminTab.class);
        assertThat(context.getBean(NavigationProfilesAdminTab.class)).isNotSameAs(editor);
        assertThat(context.getBeanFactory().getBeanDefinition("navigationProfilesAdminTab").isPrototype()).isTrue();
        ui.add(editor);
        editor.refresh();
        var name = components(editor).filter(TextField.class::isInstance).map(TextField.class::cast)
            .filter(field -> field.getLabel().equals("Название нового профиля")).findFirst().orElseThrow();
        var profiles = (ComboBox<NavigationProfile>) components(editor).filter(ComboBox.class::isInstance)
            .map(ComboBox.class::cast).filter(field -> field.getLabel().equals("Профиль интерфейса")).findFirst().orElseThrow();
        name.setValue("KI-4 editor A");
        button(editor, "Создать профиль").click();
        long firstId = profiles.getValue().id();
        visibility(editor, "Единицы измерения").setValue(NavigationVisibility.HIDE);
        button(editor, "Сохранить").click();
        name.setValue("KI-4 editor B");
        button(editor, "Создать профиль").click();
        long secondId = profiles.getValue().id();
        visibility(editor, "Единицы измерения").setValue(NavigationVisibility.SHOW);
        button(editor, "Сохранить").click();
        assertThat(firstId).isNotEqualTo(secondId);
        assertThat(administration.profile(firstId).overrides()).containsEntry("entry:unit-of-measurement", NavigationVisibility.HIDE);
        assertThat(administration.profile(secondId).overrides()).containsEntry("entry:unit-of-measurement", NavigationVisibility.SHOW);
        var anotherEditor = context.getBean(NavigationProfilesAdminTab.class);
        anotherEditor.refresh();
        assertThat(administration.listProfiles()).anyMatch(profile -> profile.id() == firstId)
            .anyMatch(profile -> profile.id() == secondId);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-editor-user", "", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        var denied = context.getBean(NavigationProfilesAdminTab.class);
        denied.refresh();
        assertThat(denied.isEnabled()).isFalse();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ComboBox<NavigationVisibility> visibility(Component editor, String title) {
        TreeGrid tree = components(editor).filter(TreeGrid.class::isInstance).map(TreeGrid.class::cast)
            .filter(grid -> grid.getColumnByKey("visibility") != null).findFirst().orElseThrow();
        var data = tree.getTreeData();
        Object row = flatten(data.getRootItems(), data::getChildren).filter(value -> {
            try { var method = value.getClass().getDeclaredMethod("title"); method.setAccessible(true); return title.equals(method.invoke(value)); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }).findFirst().orElseThrow();
        return (ComboBox<NavigationVisibility>) ((ComponentRenderer) tree.getColumnByKey("visibility").getRenderer()).createComponent(row);
    }
    private static Stream<Object> flatten(List<?> rows, java.util.function.Function<Object, List<?>> children) {
        return rows.stream().flatMap(row -> Stream.concat(Stream.of(row), flatten(children.apply(row), children)));
    }
    private static Button button(Component root, String title) {
        return components(root).filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> button.getText().equals(title)).findFirst().orElseThrow();
    }
    private static Stream<Component> components(Component root) {
        return root.getChildren().flatMap(child -> Stream.concat(Stream.of(child), components(child)));
    }
}
