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
@SpringBootTest(classes = Application.class, properties = {
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
    @Autowired org.ipro.navigation.NavigationStructureService structures;
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

        var gate = org.mockito.Mockito.mock(org.ipro.rls.RlsReadGate.class);
        org.mockito.Mockito.when(gate.canRead(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        var oldUi = new NavigationUiSnapshot(catalogs, new org.ipro.navigation.NavigationResolver(), profiles, gate, () -> "navigation-test-admin");
        var before = menuNodes(oldUi);
        var structure = structures.structure();
        var target = structure.sections().stream().filter(section -> section.parentId() == null
            && !section.id().equals("directories")).findFirst().orElseThrow();
        var moved = structure.entries().stream().map(entry -> entry.id().equals("unit-of-measurement")
            ? new org.ipro.navigation.NavigationStructure.Entry(entry.id(), target.id(), entry.functionKey(), null, 0)
            : entry).toList();
        structures.save(structure.version(), new org.ipro.navigation.NavigationStructure(
            structure.version(), structure.seededAt(), structure.sections(), moved));
        var newUi = new NavigationUiSnapshot(catalogs, new org.ipro.navigation.NavigationResolver(), profiles, gate, () -> "navigation-test-admin");
        assertThat(sectionWithUnits(before)).isEqualTo("directories");
        assertThat(sectionWithUnits(menuNodes(newUi))).isEqualTo(target.id());
        assertThat(sectionWithUnits(menuNodes(oldUi))).isEqualTo("directories");
    }

    private static void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "navigation-test-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
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
