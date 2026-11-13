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
    @Autowired org.ipro.navigation.NavigationStructureService structures;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.ipro.form.action.OperationPermissionSource operationPermissions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    org.ipro.rls.RlsUiGate rlsUiGate;
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void catalogIsDerivedFromRegistryAndItemKeysAreTheRouteKeys() {
        var catalog = catalogs.get();
        assertThat(catalog.roots()).extracting(org.ipro.navigation.NavigationNode::id)
            .contains("directories", "production")
            .doesNotContain("documents");
        assertThat(catalog.findSubsystem("production-documents")).isEmpty();
        assertThat(catalog.findItem("prd-spec")).hasValueSatisfying(item -> {
            assertThat(item.title()).isEqualTo("Спецификации");
            assertThat(item.function().key()).isEqualTo("list:prd-spec");
        });
        assertThat(catalog.findItem("receiving-document")).hasValueSatisfying(item -> {
            assertThat(item.title()).isEqualTo("Приёмно-сдаточные накладные");
            assertThat(item.function().key()).isEqualTo("list:receiving-document");
        });
        var items = new java.util.ArrayList<org.ipro.navigation.NavigationItem>();
        var pending = new java.util.ArrayDeque<>(catalog.roots());
        while (!pending.isEmpty()) {
            var node = pending.poll();
            items.addAll(node.items());
            pending.addAll(node.children());
        }
        assertThat(items).extracting(org.ipro.navigation.NavigationItem::entityClass)
            .contains(org.ip.model.ReceivingDocument.class);
        assertThat(items).isNotEmpty().allSatisfy(item -> {
            assertThat(item.defaultVisible()).isTrue();
            assertThat(routes.find(item.entityClass())).hasValueSatisfying(route ->
                assertThat(item.id()).isEqualTo(route.entityKey()));
        });
        assertThat(catalog.findItem("unit-of-measurement")).isPresent();
        assertThat(catalog.findItem("workshop")).isPresent();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void savedPlacementAppearsAtItsNewDestinationInTheNextMenuSnapshot() {
        authenticateAdmin();
        var profiles = org.mockito.Mockito.mock(NavigationProfileReader.class);
        var availability = org.mockito.Mockito.mock(ApplicationNavigationCommands.class);
        org.mockito.Mockito.when(availability.decide(org.mockito.ArgumentMatchers.any(org.ipro.navigation.NavigationFunction.class)))
            .thenReturn(org.ipro.form.action.ActionDecision.allowed());
        var oldUi = new NavigationUiSnapshot(catalogs, new org.ipro.navigation.NavigationResolver(), profiles, availability);
        var before = menuNodes(oldUi);
        var structure = structures.structure();
        var target = structure.sections().stream().filter(section -> section.parentId() == null
            && !section.id().equals("directories")).findFirst().orElseThrow();
        var moved = structure.entries().stream().map(entry -> entry.id().equals("unit-of-measurement")
            ? new org.ipro.navigation.NavigationStructure.Entry(entry.id(), target.id(), entry.functionKey(), null, 0)
            : entry).toList();
        structures.save(structure.version(), new org.ipro.navigation.NavigationStructure(
            structure.version(), structure.seededAt(), structure.sections(), moved));
        var newUi = new NavigationUiSnapshot(catalogs, new org.ipro.navigation.NavigationResolver(), profiles, availability);
        assertThat(sectionWithUnits(before)).isEqualTo("directories");
        assertThat(sectionWithUnits(menuNodes(newUi))).isEqualTo(target.id());
        assertThat(sectionWithUnits(menuNodes(oldUi))).isEqualTo("directories");
    }

    private static List<org.ipro.navigation.NavigationTree.Node> menuNodes(NavigationUiSnapshot snapshot) {
        var menu = new com.vaadin.flow.component.sidenav.SideNav();
        var nodes = new java.util.ArrayList<org.ipro.navigation.NavigationTree.Node>();
        snapshot.appendMenu(menu, nodes::add);
        for (var item : menu.getItems()) {
            item.getElement().getNode().getFeature(com.vaadin.flow.internal.nodefeature.ElementListenerMap.class)
                .fireEvent(new com.vaadin.flow.dom.DomEvent(item.getElement(), "click",
                    tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()));
        }
        return nodes;
    }

    private static String sectionWithUnits(List<org.ipro.navigation.NavigationTree.Node> nodes) {
        return nodes.stream().flatMap(root -> root.groupsRecursive().stream())
            .filter(group -> group.items().stream().anyMatch(item -> item.id().equals("unit-of-measurement")))
            .map(org.ipro.navigation.NavigationTree.Group::subsystemId).findFirst().orElseThrow();
    }

    @Test
    void createCommandUsesTheListDecisionAndOpensTheNewItem() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-test-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var command = function("create:nomenclature");
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
    @org.springframework.transaction.annotation.Transactional
    void profileShowStillRequiresCreatePermissionAndVisibleAncestor() {
        authenticateAdmin();
        var function = function("create:nomenclature");
        var snapshot = structures.structure();
        var section = snapshot.sections().stream().filter(row -> "directories".equals(row.areaKey()))
            .findFirst().orElseThrow();
        var entries = new java.util.ArrayList<>(snapshot.entries());
        entries.add(new org.ipro.navigation.NavigationStructure.Entry("create-nomenclature",
            section.id(), function.key(), null, 0));
        var saved = structures.save(snapshot.version(), new org.ipro.navigation.NavigationStructure(
            snapshot.version(), snapshot.seededAt(), snapshot.sections(), entries));
        var placement = saved.entries().stream().filter(row -> function.key().equals(row.functionKey()))
            .findFirst().orElseThrow();
        var catalog = catalogs.get();
        var item = catalog.findItem(placement.id()).orElseThrow();
        var resolver = new org.ipro.navigation.NavigationResolver();
        var gateway = org.mockito.Mockito.mock(org.ipro.form.spi.WorkspaceGateway.class);
        var navigator = coordinator(gateway);
        var profile = administration.createProfile("Проверка СМ-2");
        var enabled = administration.saveProfile(profile.id(), profile.version(),
            Map.of(item.key(), NavigationVisibility.SHOW));
        var show = enabled.overrides();
        java.util.function.Predicate<org.ipro.navigation.NavigationItem> available =
            entry -> !entry.function().command() || commands.decide(entry.function()).actionable();

        org.mockito.Mockito.doReturn(null).when(operationPermissions)
            .denialReason(org.ip.model.Nomenclature.class, org.ipro.data.DataOperation.CREATE);
        org.mockito.Mockito.doReturn(new org.ipro.rls.RlsUiGate.AccessDecision(true, null))
            .when(rlsUiGate).canCreate(org.ip.model.Nomenclature.class);
        assertThat(resolver.resolve(catalog, Map.of(), available).findSubsystem(section.id()).orElseThrow().items())
            .doesNotContain(item);
        assertThat(resolver.resolve(catalog, show, available).findSubsystem(section.id()).orElseThrow().items())
            .contains(item);
        assertThat(commands.execute(item.function(), navigator).actionable()).isTrue();
        org.mockito.Mockito.verify(gateway).open(
            org.mockito.ArgumentMatchers.eq(org.ipro.form.coordinator.ItemFormWrapperView.class),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.startsWith("Создание: "),
            org.mockito.ArgumentMatchers.any());
        assertThat(resolver.resolve(catalog, Map.of(item.key(), NavigationVisibility.SHOW,
            "section:" + section.id(), NavigationVisibility.HIDE), available).findSubsystem(section.id())).isEmpty();

        org.mockito.Mockito.doReturn("Нет гранта").when(operationPermissions)
            .denialReason(org.ip.model.Nomenclature.class, org.ipro.data.DataOperation.CREATE);
        assertThat(resolver.resolve(catalog, show, available).findSubsystem(section.id()).orElseThrow().items())
            .doesNotContain(item);
        assertThat(commands.execute(item.function(), navigator).actionable()).isFalse();
        org.mockito.Mockito.verifyNoMoreInteractions(gateway);
    }

    private org.ipro.form.coordinator.FormCoordinator coordinator(org.ipro.form.spi.WorkspaceGateway gateway) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<org.ipro.form.spi.WorkspaceGateway> gateways =
            org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(gateways.getIfAvailable()).thenReturn(gateway);
        var coordinator = new org.ipro.form.coordinator.FormCoordinator(
            context.getBean(org.ipro.metadata.MetadataResolver.class),
            context.getBean(org.ipro.form.FieldFactory.class), context,
            context.getBean(org.ipro.form.registry.FormResolver.class),
            context.getBean(org.ipro.crud.ServiceLocator.class), null, null, rlsUiGate,
            context.getBean(org.ipro.form.coordinator.ItemFormAccessBinder.class),
            context.getBean(org.ipro.form.action.ActionResolvers.class),
            context.getBean(org.ipro.form.link.FormLinkService.class),
            context.getBean(org.ipro.crud.EntityCopyService.class),
            context.getBean(org.ipro.form.TableSectionFactory.class), gateways, null);
        coordinator.setItemFormOpenMode(org.ipro.form.coordinator.FormOpenMode.WORKSPACE_TAB);
        return coordinator;
    }

    @Test
    void functionCatalogIncludesEligibleListsAndCommands() {
        authenticateAdmin();
        assertThatThrownBy(() -> new org.ipro.form.action.ActionHandlerRegistry(List.of(CommandFixture.navigationRowHandler())))
            .hasMessageContaining("Invalid NAVIGATION handler: test.row");
        assertThat(structures.functions()).extracting(org.ipro.navigation.NavigationFunction::key)
            .contains("list:nomenclature", "create:nomenclature", "list:prd-spec", "list:receiving-document",
                "list:nomenclature:context-test", "list:nomenclature:navigation-test",
                "list:receiving-document:workshop-incoming", "action:nomenclature:test.inspect")
            .doesNotContain("create:attribute-value", "create:prd-spec", "create:receiving-document",
                "action:nomenclature:test.row");
    }

    private org.ipro.navigation.NavigationFunction function(String key) {
        return structures.functions().stream().filter(function -> function.key().equals(key)).findFirst().orElseThrow();
    }

    private static void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-test-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @Test
    void navigationHandlerReceivesNoRowOrFormContext() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-test-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var command = function("action:nomenclature:test.inspect");
        var navigator = org.mockito.Mockito.mock(org.ipro.form.coordinator.FormNavigator.class);
        assertThat(commands.execute(command, navigator).actionable()).isTrue();
        assertThat(commandFixture.invocation).isEqualTo(new org.ipro.form.action.ActionInvocation(
            org.ip.model.Nomenclature.class, null, null, navigator, null, Map.of(), Map.of()));
    }

    @Test
    void registeredListVariantUsesTheReadGateAndOpensTheNamedList() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-test-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var command = function("list:nomenclature:navigation-test");
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

        static org.ipro.form.action.ActionHandler navigationRowHandler() {
            return new org.ipro.form.action.ActionHandler() {
                @Override
                public org.ipro.form.action.ActionDefinition definition() {
                    return org.ipro.form.action.ActionDefinition.forEntity(
                        org.ipro.form.action.ActionId.of("test.row"), org.ipro.form.action.ActionSurface.NAVIGATION,
                        org.ip.model.Nomenclature.class, "Row", null, 0,
                        org.ipro.form.action.ActionRequirement.selectionOnly());
                }

                @Override
                public void execute(org.ipro.form.action.ActionInvocation value) {
                    throw new AssertionError("A row action cannot be invoked from the menu");
                }
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
            "entry:unit-of-measurement", NavigationVisibility.HIDE));
        assertThat(administration.profile(saved.id())).isEqualTo(saved);
        assertThat(administration.resetProfile(saved.id(), saved.version()).overrides()).isEmpty();
        // An authenticated name without a persisted User.id must not silently get a default profile.
        assertThatThrownBy(reader::currentProfile).isInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-test-user", "", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertThatThrownBy(() -> administration.createProfile("Forbidden")).isInstanceOf(AccessDeniedException.class);
    }
}
