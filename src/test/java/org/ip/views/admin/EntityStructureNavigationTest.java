package org.ip.views.admin;

import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityExposure;
import org.ipro.form.link.EntityStructureNavigation;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.PublishedFormRoute;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EntityStructureNavigationTest {
    static class Root {}
    static class OtherRoot {}
    static class Row {}
    static class Missing {}

    private final EntityExplorerAccess access = mock(EntityExplorerAccess.class);
    private final FormRouteCatalog routes = mock(FormRouteCatalog.class);
    private final ExplorerTreeModel.CatalogView facts = mock(ExplorerTreeModel.CatalogView.class);
    private final List<String> opens = new ArrayList<>();
    private final List<String> details = new ArrayList<>();
    private final AtomicInteger reads = new AtomicInteger();
    private final EntityExplorerNavigation navigation = new EntityExplorerNavigation(access, routes,
        () -> { reads.incrementAndGet(); return facts; }, details::add);

    private void available(boolean published, List<ExplorerSnapshot.OwnedSection> sections) {
        when(access.allows()).thenReturn(true);
        List<ExplorerSnapshot.Entry> roots = List.of(entry(Root.class), entry(OtherRoot.class));
        when(facts.roots()).thenReturn(roots);
        when(facts.sectionsOf(Root.class)).thenReturn(sections);
        when(facts.sectionsOf(OtherRoot.class)).thenReturn(List.of());
        if (published) {
            PublishedFormRoute route = mock(PublishedFormRoute.class);
            when(route.entityKey()).thenReturn("roots");
            when(routes.find(Root.class)).thenReturn(Optional.of(route));
        }
        navigation.bindHost(this, (type, anchor) -> { opens.add(type.getSimpleName() + "@" + anchor); return true; });
    }

    @Test void aPublishedRootOpensThroughOneHostCallWithItsActualAddress() {
        available(true, List.of());
        assertThat(navigation.link(Root.class)).contains("/entity-explorer/roots");
        assertThat(navigation.open(Root.class, "reading/paths"))
            .isEqualTo(new EntityStructureNavigation.OpenResult.Opened(Root.class,
                Optional.of("/entity-explorer/roots?view=reading/paths")));
        assertThat(opens).containsExactly("Root@reading/paths");
    }

    @Test void anUnpublishedRootOpensProgrammaticallyWithoutInventingAUrl() {
        available(false, List.of());
        assertThat(navigation.availability(Root.class).available()).isTrue();
        assertThat(navigation.link(Root.class)).isEmpty();
        assertThat(navigation.open(Root.class, null))
            .isEqualTo(new EntityStructureNavigation.OpenResult.Opened(Root.class, Optional.empty()));
        assertThat(opens).containsExactly("Root@null");
    }

    @Test void anOwnedRowUsesTheConfirmedRootAndTheExistingTableSectionsAnchor() {
        available(true, List.of(section(Root.class)));
        assertThat(navigation.link(Row.class)).contains("/entity-explorer/roots?view=fields/table-sections");
        assertThat(navigation.open(Row.class, null)).isInstanceOf(EntityStructureNavigation.OpenResult.Opened.class);
        assertThat(opens).containsExactly("Root@fields/table-sections");
        assertThat(details).singleElement().asString().contains("Строки", "Row");
    }

    @Test void missingAndAmbiguousOwnersDoNotChooseAnArbitraryRoot() {
        available(true, List.of());
        assertThat(navigation.open(Row.class, null)).isInstanceOf(EntityStructureNavigation.OpenResult.Unavailable.class);
        when(facts.sectionsOf(Root.class)).thenReturn(List.of(section(Root.class)));
        when(facts.sectionsOf(OtherRoot.class)).thenReturn(List.of(section(OtherRoot.class)));
        assertThat(navigation.availability(Row.class).reason()).contains("Неоднозначный владелец");
        assertThat(navigation.link(Row.class)).isEmpty();
        assertThat(navigation.open(Row.class, null)).isInstanceOf(EntityStructureNavigation.OpenResult.Unavailable.class);
        assertThat(opens).isEmpty();
    }

    @Test void accessIsCheckedBeforeReadingFactsAndAgainAfterShowingTheAction() {
        assertThat(navigation.availability(Root.class).available()).isFalse();
        assertThat(navigation.link(Root.class)).isEmpty();
        assertThat(navigation.open(Root.class, null)).isInstanceOf(EntityStructureNavigation.OpenResult.Unavailable.class);
        assertThat(reads).hasValue(0);
        available(true, List.of());
        assertThat(navigation.availability(Root.class).available()).isTrue();
        when(access.allows()).thenReturn(false);
        assertThat(navigation.open(Root.class, null)).isInstanceOf(EntityStructureNavigation.OpenResult.Unavailable.class);
        assertThat(opens).isEmpty();
    }

    @Test void missingHostAndHostRefusalNeverReportSuccessfulOpening() {
        available(true, List.of());
        navigation.unbindHost(this);
        assertThat(navigation.open(Root.class, null)).isEqualTo(new EntityStructureNavigation.OpenResult.Unavailable(
            "В текущем UI нет рабочей области для Entity Explorer"));
        navigation.bindHost(this, (type, anchor) -> false);
        assertThat(navigation.open(Root.class, null)).isInstanceOf(EntityStructureNavigation.OpenResult.Unavailable.class);
        assertThat(opens).isEmpty();
    }

    @Test void anUnknownAnchorOrTypeDoesNotOpenTheHost() {
        available(true, List.of());
        assertThat(navigation.open(Root.class, "reading/unknown")).isInstanceOf(EntityStructureNavigation.OpenResult.Unavailable.class);
        assertThat(navigation.open(Missing.class, null)).isInstanceOf(EntityStructureNavigation.OpenResult.Unavailable.class);
        assertThat(opens).isEmpty();
    }

    @Test void aPreviousHostCannotUnbindItsReplacementAndOtherUiStateIsIndependent() {
        available(true, List.of());
        Object replacement = new Object();
        navigation.bindHost(replacement, (type, anchor) -> { opens.add("replacement"); return true; });
        navigation.unbindHost(this);
        EntityExplorerNavigation other = new EntityExplorerNavigation(access, routes, () -> facts, text -> {});
        assertThat(other.open(Root.class, null)).isInstanceOf(EntityStructureNavigation.OpenResult.Unavailable.class);
        navigation.open(Root.class, null);
        navigation.open(Root.class, null);
        assertThat(opens).containsExactly("replacement", "replacement");
        assertThat(EntityExplorerNavigation.class.getAnnotation(com.vaadin.flow.spring.annotation.UIScope.class)).isNotNull();
    }

    @Test void theSpringContainerStoresIndependentHostsInTheRealVaadinUiScope() {
        available(true, List.of());
        var session = mock(com.vaadin.flow.server.VaadinSession.class);
        when(session.getState()).thenReturn(com.vaadin.flow.server.VaadinSessionState.OPEN);
        when(session.hasLock()).thenReturn(true);
        when(session.getLockInstance()).thenReturn(new java.util.concurrent.locks.ReentrantLock());
        java.util.Map<Class<?>, Object> attributes = new java.util.HashMap<>();
        when(session.getAttribute(any(Class.class))).thenAnswer(call -> attributes.get(call.getArgument(0)));
        doAnswer(call -> { attributes.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(session).setAttribute(any(Class.class), any());
        var uiA = mock(com.vaadin.flow.component.UI.class);
        var uiB = mock(com.vaadin.flow.component.UI.class);
        when(uiA.getUIId()).thenReturn(1);
        when(uiB.getUIId()).thenReturn(2);
        when(uiA.getSession()).thenReturn(session);
        when(uiB.getSession()).thenReturn(session);
        var view = mock(EntityExplorerView.class);
        when(view.structureCatalog()).thenReturn(facts);
        com.vaadin.flow.server.VaadinSession.setCurrent(session);
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.addBeanFactoryPostProcessor(new com.vaadin.flow.spring.scopes.VaadinUIScope());
            context.registerBean(EntityExplorerAccess.class, () -> access);
            context.registerBean(FormRouteCatalog.class, () -> routes);
            context.registerBean(EntityExplorerView.class, () -> view);
            context.register(EntityExplorerNavigation.class);
            context.refresh();
            com.vaadin.flow.component.UI.setCurrent(uiA);
            var first = context.getBean(EntityExplorerNavigation.class);
            first.bindHost(uiA, (type, anchor) -> { opens.add("UI A"); return true; });
            assertThat(context.getBean(EntityExplorerNavigation.class)).isSameAs(first);

            com.vaadin.flow.component.UI.setCurrent(uiB);
            var second = context.getBean(EntityExplorerNavigation.class);
            assertThat(second).isNotSameAs(first);
            assertThat(second.open(Root.class, null)).isInstanceOf(EntityStructureNavigation.OpenResult.Unavailable.class);
            second.bindHost(uiB, (type, anchor) -> { opens.add("UI B"); return true; });
            second.open(Root.class, null);
            com.vaadin.flow.component.UI.setCurrent(uiA);
            context.getBean(EntityExplorerNavigation.class).open(Root.class, null);
            assertThat(opens).containsExactly("UI B", "UI A");
        } finally {
            com.vaadin.flow.component.UI.setCurrent(null);
            com.vaadin.flow.server.VaadinSession.setCurrent(null);
        }
    }

    private static ExplorerSnapshot.OwnedSection section(Class<?> owner) {
        return new ExplorerSnapshot.OwnedSection(owner, owner.getName(), "parent", Row.class,
            Row.class.getName(), "Row", ResolvedValue.code("Строки"), 1);
    }

    private static ExplorerSnapshot.Entry entry(Class<?> type) {
        EntityDescriptor descriptor = mock(EntityDescriptor.class);
        when(descriptor.exposure()).thenReturn(EntityExposure.STANDARD_ROOT);
        return new ExplorerSnapshot.Entry(type, type.getName(), type.getSimpleName(), ResolvedValue.code(type.getSimpleName()),
            EntityKind.CATALOG, descriptor, Optional.empty(), Optional.empty(), List.of(),
            ExplorerSnapshot.EntryState.READY, "", List.of(), 0, 0, true);
    }
}
