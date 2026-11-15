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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real application wiring, including the RLS flush guard (navigation is always on). */
@SpringBootTest(classes = {Application.class, NavigationApplicationSmokeTest.CommandFixture.class}, properties = {
    "app.demo-data.enabled=false", "rls.write-boundary.enforced=true",
    "spring.datasource.url=jdbc:h2:mem:navigation-ki2-app;DB_CLOSE_DELAY=-1",
    "vaadin.allowed-packages=org.ip.views.login,com.vaadin", "vaadin.productionMode=true"})
@ContextConfiguration(initializers = NavigationSmokeIsolation.class)
class NavigationApplicationSmokeTest {
    @Autowired NavigationAdministrationService administration;
    @Autowired NavigationProfileReader reader;
    @Autowired org.springframework.context.ConfigurableApplicationContext context;
    @Autowired org.ipro.navigation.NavigationCatalogProvider catalogs;
    @Autowired org.ipro.form.link.FormRouteCatalog routes;
    @Autowired ApplicationNavigationCommands commands;
    @Autowired CommandFixture commandFixture;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.ipro.form.action.OperationPermissionSource operationPermissions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    org.ipro.rls.RlsUiGate rlsUiGate;
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void catalogIsDerivedFromRegistryAndItemKeysAreTheRouteKeys() {
        var catalog = catalogs.get();
        assertThat(catalog.roots()).extracting(org.ipro.navigation.NavigationNode::id)
            .contains("directories", "documents", "production");
        assertThat(catalog.findSubsystem("production-documents")).isPresent();
        var items = new java.util.ArrayList<org.ipro.navigation.NavigationItem>();
        var pending = new java.util.ArrayDeque<>(catalog.roots());
        while (!pending.isEmpty()) {
            var node = pending.poll();
            items.addAll(node.items());
            pending.addAll(node.children());
        }
        assertThat(items).isNotEmpty().allSatisfy(item -> {
            assertThat(item.defaultVisible()).isTrue();
            assertThat(routes.find(item.entityClass())).hasValueSatisfying(route -> {
                if (item.additional()) {
                    assertThat(item.id()).endsWith("/" + route.entityKey());
                } else {
                    assertThat(item.id()).isEqualTo(route.entityKey());
                }
            });
        });
        assertThat(catalog.findItem("unit-of-measurement")).isPresent();
        assertThat(catalog.findItem("workshop")).hasValueSatisfying(item -> assertThat(item.additional()).isFalse());
        assertThat(catalog.findItem("production/workshop")).hasValueSatisfying(item -> {
            assertThat(item.additional()).isTrue();
            assertThat(item.key()).isEqualTo("item:production/workshop");
        });
        assertThat(catalog.findItem("production/nomenclature")).isPresent();
    }

    @Test
    void profileCanHideEitherPlacementIndependentlyAndShowStillRequiresRead() {
        var catalog = catalogs.get();
        var resolver = new org.ipro.navigation.NavigationResolver();
        var additionalHidden = resolver.resolve(catalog,
            Map.of("item:production/workshop", NavigationVisibility.HIDE), type -> true);
        assertThat(additionalHidden.findSubsystem("production").orElseThrow().items())
            .noneMatch(item -> item.id().equals("production/workshop"));
        assertThat(additionalHidden.findSubsystem("directories").orElseThrow().items())
            .anyMatch(item -> item.id().equals("workshop"));
        var primaryHidden = resolver.resolve(catalog,
            Map.of("item:workshop", NavigationVisibility.HIDE), type -> true);
        assertThat(primaryHidden.findSubsystem("directories").orElseThrow().items())
            .noneMatch(item -> item.id().equals("workshop"));
        assertThat(primaryHidden.findSubsystem("production").orElseThrow().items())
            .anyMatch(item -> item.id().equals("production/workshop"));
        var denied = resolver.resolve(catalog, Map.of("item:production/workshop", NavigationVisibility.SHOW),
            type -> type != org.ip.model.Workshop.class);
        assertThat(denied.findSubsystem("production").orElseThrow().items())
            .noneMatch(item -> item.entityClass() == org.ip.model.Workshop.class);
        assertThat(denied.findSubsystem("directories").orElseThrow().items())
            .noneMatch(item -> item.entityClass() == org.ip.model.Workshop.class);
    }

    @Test
    void declaredCommandsNeedAnExplicitShowAndAnAvailableAction() {
        var catalog = catalogs.get();
        var resolver = new org.ipro.navigation.NavigationResolver();
        String key = "command:production/nomenclature/crud.create";
        assertThat(catalog.keys()).contains(key, "command:directories/nomenclature/crud.create");
        assertThat(resolver.resolve(catalog, Map.of(), type -> true, command -> true)
            .findSubsystem("production").orElseThrow().commands()).isEmpty();
        assertThat(resolver.resolve(catalog, Map.of(key, NavigationVisibility.SHOW), type -> true, command -> false)
            .findSubsystem("production").orElseThrow().commands()).isEmpty();
        assertThat(resolver.resolve(catalog, Map.of(key, NavigationVisibility.SHOW), type -> true, command -> true)
            .findSubsystem("production").orElseThrow().commands())
            .extracting(org.ipro.navigation.NavigationCommand::key).containsExactly(key);
        assertThat(resolver.resolve(catalog, Map.of(key, NavigationVisibility.SHOW,
                "subsystem:production", NavigationVisibility.HIDE), type -> true, command -> true)
            .findSubsystem("production")).isEmpty();
    }

    @Test
    void createCommandUsesTheListDecisionAndOpensTheNewItem() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-test-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var command = catalogs.get().findSubsystem("production").orElseThrow().commands().getFirst();
        var navigator = org.mockito.Mockito.mock(org.ipro.form.coordinator.FormNavigator.class);
        org.mockito.Mockito.doReturn(null).when(operationPermissions)
            .denialReason(org.ip.model.Nomenclature.class, org.ipro.data.DataOperation.CREATE);
        org.mockito.Mockito.doReturn(new org.ipro.rls.RlsUiGate.AccessDecision(true, null))
            .when(rlsUiGate).canCreate(org.ip.model.Nomenclature.class);
        assertThat(commands.execute(command, navigator).actionable()).isTrue();
        org.mockito.Mockito.verify(navigator).openItemForm(org.ip.model.Nomenclature.class, null, null, null, null);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-test-user", "", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        org.mockito.Mockito.doReturn("Нет гранта").when(operationPermissions)
            .denialReason(org.ip.model.Nomenclature.class, org.ipro.data.DataOperation.CREATE);
        assertThat(commands.execute(command, navigator).actionable()).isFalse();
        org.mockito.Mockito.verifyNoMoreInteractions(navigator);
    }

    @Test
    void invalidCommandsAreRejectedBeforeTheyReachProfiles() {
        assertThatThrownBy(() -> commands.validate(command(org.ip.model.AttributeValue.class, "crud.create", "")))
            .hasMessageContaining("suppressed");
        assertThatThrownBy(() -> commands.validate(command(org.ip.model.PrdSpec.class, "crud.create", "")))
            .hasMessageContaining("requires context");
        assertThatThrownBy(() -> commands.validate(command(org.ip.model.Nomenclature.class, "", "missing")))
            .hasMessageContaining("variant is not registered");
        assertThatThrownBy(() -> commands.validate(command(org.ip.model.Nomenclature.class, "", "context-test")))
            .hasMessageContaining("variant requires context");
        assertThatThrownBy(() -> commands.validate(command(org.ip.model.Nomenclature.class, "test.missing", "")))
            .hasMessageContaining("NAVIGATION action is not registered");
        assertThatThrownBy(() -> commands.validate(command(String.class, "test.inspect", "")))
            .hasMessageContaining("entity must be managed and published");
    }

    private static org.ipro.navigation.NavigationCommand command(Class<?> type, String action, String variant) {
        return new org.ipro.navigation.NavigationCommand("command:test", type, action, variant, "Test", "", 0);
    }

    @Test
    void navigationHandlerReceivesNoRowOrFormContext() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-test-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var command = command(org.ip.model.Nomenclature.class, "test.inspect", "");
        var navigator = org.mockito.Mockito.mock(org.ipro.form.coordinator.FormNavigator.class);
        commands.validate(command);
        assertThat(commands.execute(command, navigator).actionable()).isTrue();
        assertThat(commandFixture.invocation).isEqualTo(new org.ipro.form.action.ActionInvocation(
            org.ip.model.Nomenclature.class, null, null, navigator, null, Map.of(), Map.of()));
    }

    @Test
    void registeredListVariantUsesTheReadGateAndOpensTheNamedList() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-test-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var command = command(org.ip.model.Nomenclature.class, "", "navigation-test");
        commands.validate(command);
        var navigator = org.mockito.Mockito.mock(org.ipro.form.coordinator.FormNavigator.class);
        assertThat(commands.execute(command, navigator).actionable()).isTrue();
        org.mockito.Mockito.verify(navigator).openListForm(org.ip.model.Nomenclature.class, "navigation-test", null);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CommandFixture {
        private org.ipro.form.action.ActionInvocation invocation;

        @Bean
        org.springframework.beans.factory.InitializingBean navigationTestVariant(
                org.ipro.form.registry.FormRegistry forms) {
            return () -> {
                forms.registerListForm(org.ip.model.Nomenclature.class,
                    "navigation-test", context -> new com.vaadin.flow.component.html.Div());
                forms.registerListForm(org.ip.model.Nomenclature.class,
                    "context-test", context -> new com.vaadin.flow.component.html.Div());
                forms.registerVariantContextFilters(org.ip.model.Nomenclature.class,
                    org.ipro.form.registry.FormType.LIST, "context-test",
                    List.of(org.ipro.form.builder.ContextFilterField.requiredAuto("code", "Код")));
            };
        }

        @Bean
        org.ipro.form.action.ActionHandler navigationTestHandler() {
            return new org.ipro.form.action.ActionHandler() {
                @Override
                public org.ipro.form.action.ActionDefinition definition() {
                    return org.ipro.form.action.ActionDefinition.forEntity(
                        org.ipro.form.action.ActionId.of("test.inspect"), org.ipro.form.action.ActionSurface.NAVIGATION,
                        org.ip.model.Nomenclature.class, "Inspect", null, 0,
                        org.ipro.form.action.ActionRequirement.none());
                }

                @Override
                public void execute(org.ipro.form.action.ActionInvocation value) {
                    invocation = value;
                }
            };
        }
    }

    @Test
    void ownerOperationsWorkInsideActualApplicationAndSecurityDoesNotRequireAnAopProxy() {
        assertThat(context.getBeanFactory().getBeanDefinition("navigationUiSnapshot").getScope()).isEqualTo("vaadin-ui");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-test-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var created = administration.createProfile("KI-2 app smoke");
        var saved = administration.saveProfile(created.id(), created.version(), Map.of(
            "item:unit-of-measurement", NavigationVisibility.HIDE,
            "command:production/nomenclature/crud.create", NavigationVisibility.SHOW));
        assertThat(administration.profile(saved.id())).isEqualTo(saved);
        assertThat(administration.resetProfile(saved.id(), saved.version()).overrides()).isEmpty();
        // An authenticated name without a persisted User.id must not silently get a default profile.
        assertThatThrownBy(reader::currentProfile).isInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-test-user", "", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertThatThrownBy(() -> administration.createProfile("Forbidden")).isInstanceOf(AccessDeniedException.class);
    }
}
