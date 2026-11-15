package org.ip.navigation;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.VaadinSessionState;
import com.vaadin.flow.spring.scopes.VaadinUIScope;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.Workshop;
import org.ip.views.workspace.SubsystemHomeView;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.navigation.NavigationCatalog;
import org.ipro.navigation.NavigationFunctionsProvider;
import org.ipro.navigation.NavigationMenuReader;
import org.ipro.navigation.NavigationResolver;
import org.ipro.navigation.NavigationTree;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.Test;
import org.navigationfixture.Fixtures;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class NavigationUiSnapshotTest {
    private static NavigationCatalog nestedCatalog() {
        return NavigationCatalogFixtures.nested();
    }

    @Test void realUiScopeLoadsEachUsersMenuOnce() {
        var user = new AtomicReference<>("alice");
        var reads = new AtomicInteger();
        var menuReads = new AtomicInteger();
        var rootList = NavigationCatalogFixtures.list("root-item", Fixtures.RootItem.class, "Root list", "directories");
        var units = NavigationCatalogFixtures.list("unit-of-measurement", UnitOfMeasurement.class, "Units", "directories");
        var workshops = NavigationCatalogFixtures.list("workshop", Workshop.class, "Workshops", "directories");
        var session = mock(VaadinSession.class);
        when(session.getState()).thenReturn(VaadinSessionState.OPEN);
        when(session.hasLock()).thenReturn(true);
        when(session.getLockInstance()).thenReturn(new ReentrantLock());
        Map<Class<?>, Object> attributes = new HashMap<>();
        when(session.getAttribute(any(Class.class))).thenAnswer(call -> attributes.get(call.getArgument(0)));
        doAnswer(call -> { attributes.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(session).setAttribute(any(Class.class), any());
        var uiA = mock(UI.class);
        var uiB = mock(UI.class);
        when(uiA.getUIId()).thenReturn(1);
        when(uiB.getUIId()).thenReturn(2);
        when(uiA.getSession()).thenReturn(session);
        when(uiB.getSession()).thenReturn(session);
        VaadinSession.setCurrent(session);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.addBeanFactoryPostProcessor(new VaadinUIScope());
            context.registerBean(NavigationResolver.class, NavigationResolver::new);
            context.registerBean(NavigationFunctionsProvider.class, () -> new NavigationFunctionsProvider(
                NavigationCatalogFixtures.functions(List.of(rootList, units, workshops))));
            context.registerBean(NavigationMenuReader.class, () -> () -> {
                menuReads.incrementAndGet();
                var lists = user.get().equals("alice") ? List.of(rootList, workshops) : List.of(rootList, units, workshops);
                return NavigationCatalogFixtures.structure("directories", "Directories", null, lists);
            });
            var gate = mock(RlsReadGate.class);
            when(gate.canRead(any(), anyString())).thenAnswer(call -> {
                reads.incrementAndGet();
                return call.getArgument(0) != Workshop.class;
            });
            context.registerBean(ApplicationNavigationCommands.class, () -> {
                var availability = new org.ipro.form.action.NavigationCommandAvailability(null, null,
                    type -> gate.canRead(type, user.get()));
                return new ApplicationNavigationCommands(null, availability, null);
            });
            context.register(NavigationUiSnapshot.class);
            context.refresh();
            UI.setCurrent(uiA);
            var first = context.getBean(NavigationUiSnapshot.class);
            var firstMenu = new SideNav();
            var firstNodes = new ArrayList<NavigationTree.Node>();
            first.appendMenu(firstMenu, firstNodes::add);
            assertThat(context.getBean(NavigationUiSnapshot.class)).isSameAs(first);
            click(firstMenu.getItems().getFirst());
            var directoriesA = firstNodes.getFirst();
            assertThat(directoriesA.items()).noneMatch(item -> item.entityClass() == UnitOfMeasurement.class
                || item.entityClass() == Workshop.class);
            user.set("bob");
            UI.setCurrent(uiB);
            var second = context.getBean(NavigationUiSnapshot.class);
            assertThat(second).isNotSameAs(first);
            var secondMenu = new SideNav();
            var secondNodes = new ArrayList<NavigationTree.Node>();
            second.appendMenu(secondMenu, secondNodes::add);
            click(secondMenu.getItems().getFirst());
            assertThat(secondNodes.getFirst().items()).anyMatch(item -> item.entityClass() == UnitOfMeasurement.class)
                .noneMatch(item -> item.entityClass() == Workshop.class);
            int capturedReads = reads.get();
            UI.setCurrent(uiA);
            first.appendMenu(new SideNav(), node -> {});
            assertThat(menuReads).hasValue(2);
            assertThat(reads.get()).isEqualTo(capturedReads);
            var tiles = new SubsystemHomeView(mock(FormNavigator.class), null);
            tiles.init(directoriesA);
            assertThat(descendants(tiles).filter(Button.class::isInstance).count()).isEqualTo(directoriesA.items().size());
            assertThat(reads.get()).isEqualTo(capturedReads);
            verify(gate, atLeastOnce()).canRead(any(), eq("alice"));
            verify(gate, atLeastOnce()).canRead(any(), eq("bob"));
        } finally {
            UI.setCurrent(null);
            VaadinSession.setCurrent(null);
        }
    }

    @Test void menuHoldsOnlyTopLevelSectionsAndTheirPagesStillOpenThroughFormNavigator() {
        var tree = new NavigationResolver().resolve(nestedCatalog(), item -> true);
        var snapshot = new NavigationUiSnapshot(() -> tree);
        var menu = new SideNav();
        var opened = new ArrayList<NavigationTree.Node>();
        snapshot.appendMenu(menu, opened::add);
        assertThat(menu.getItems()).extracting(SideNavItem::getLabel)
            .containsExactlyElementsOf(tree.roots().stream().map(NavigationTree.Node::title).toList());
        var navigator = mock(FormNavigator.class);
        for (int index = 0; index < tree.roots().size(); index++) {
            var root = tree.roots().get(index);
            assertThat(menu.getItems().get(index).getItems()).isEmpty();
            click(menu.getItems().get(index));
            assertThat(opened.getLast()).isSameAs(root);
            var view = new SubsystemHomeView(navigator, new ApplicationNavigationCommands(null,
                new org.ipro.form.action.NavigationCommandAvailability(null, null, type -> true), null));
            view.init(opened.getLast());
            var buttons = descendants(view).filter(Button.class::isInstance).map(Button.class::cast).toList();
            var items = root.groupsRecursive().stream().flatMap(group -> group.items().stream()).toList();
            assertThat(buttons).extracting(Button::getText).containsExactlyElementsOf(items.stream().map(item -> item.title()).toList());
            buttons.getFirst().click();
            verify(navigator).openListForm((Class) items.getFirst().entityClass(), null, null);
            view.init(root.children().getFirst());
            assertThat(descendants(view).filter(Button.class::isInstance).map(Button.class::cast).map(Button::getText))
                .containsExactly("Child list");
        }
    }

    @Test void loadFailureKeepsServiceEntriesAndDoesNotRetryOrFallBackToDefaults() {
        var attempts = new AtomicInteger();
        var snapshot = new NavigationUiSnapshot(() -> { attempts.incrementAndGet(); throw new IllegalStateException("offline"); });
        var menu = new SideNav();
        menu.addItem(new SideNavItem("Администрирование"));
        snapshot.appendMenu(menu, node -> { throw new AssertionError("no opens"); });
        assertThat(menu.getItems()).extracting(SideNavItem::getLabel)
            .containsExactly("Администрирование", NavigationUiSnapshot.LOAD_ERROR);
        assertThat(menu.getItems().getLast().isEnabled()).isFalse();
        snapshot.appendMenu(new SideNav(), node -> {});
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test void unavailableItemsDoNotAppearInParentTiles() {
        var tree = new NavigationResolver().resolve(nestedCatalog(), item -> !item.id().equals("child-item"));
        assertThat(tree.findSubsystem("child")).isEmpty();
        assertThat(tree.roots().stream().flatMap(root -> root.groupsRecursive().stream()))
            .noneMatch(group -> group.subsystemId().equals("child"));
        var view = new SubsystemHomeView(mock(FormNavigator.class), null);
        view.init(tree.roots().getFirst());
        assertThat(descendants(view).filter(Button.class::isInstance).map(Button.class::cast).map(Button::getText))
            .containsExactly("Root list");
    }

    private static Stream<Component> descendants(Component component) {
        return component.getChildren().flatMap(child -> Stream.concat(Stream.of(child), descendants(child)));
    }
    private static void click(SideNavItem item) {
        item.getElement().getNode().getFeature(com.vaadin.flow.internal.nodefeature.ElementListenerMap.class)
            .fireEvent(new com.vaadin.flow.dom.DomEvent(item.getElement(), "click", tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()));
    }
}
