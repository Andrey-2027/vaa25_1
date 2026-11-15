package org.ip.views.query;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.RouteParameters;
import jakarta.persistence.Tuple;
import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.navigation.ApplicationNavigationCommands;
import org.ip.repository.JournalRepository;
import org.ipro.filtergrid.projection.QueryProvenance;
import org.ipro.metadata.GridViewState;
import org.ipro.navigation.NavigationCatalog;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationFunctionsProvider;
import org.ipro.navigation.NavigationImportance;
import org.ipro.navigation.NavigationMenu;
import org.ipro.navigation.NavigationMenuService;
import org.ipro.navigation.NavigationResolver;
import org.ipro.navigation.NavigationStructure;
import org.ipro.query.list.QueryListDefinition;
import org.ipro.query.list.QueryListDefinition.Command;
import org.ipro.query.list.QueryListRegistry;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.ui.listform.ProjectionListForm;
import org.ipro.query.ui.listform.QueryListViews;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "app.utr.enabled=false")
class QueryListRouteIT {
    @MockitoBean DataInitializer dataInitializer;
    @Autowired ApplicationContext context;
    @Autowired QueryListRegistry lists;
    @Autowired QueryListViews views;
    @Autowired AccessGrantRepository grants;
    @Autowired JournalRepository journals;
    @Autowired ApplicationQueryActions actions;
    @Autowired ApplicationNavigationCommands menuCommands;
    @Autowired NavigationFunctionsProvider functions;
    @Autowired NavigationMenuService menus;

    @AfterEach
    void clearContext() {
        UI.setCurrent(null);
        SecurityContextHolder.clearContext();
    }

    @Test
    void registeredListEntersBothMenusAndPlacementsGoStaleWhenItCloses() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String key = "8.journals-" + suffix;
        String functionKey = "query:" + key;
        String reader = "fq8-reader-" + suffix;
        String denied = "fq8-denied-" + suffix;
        String placement = "fq8-" + suffix;
        grant(reader, journal("FQ8-" + suffix));
        login("fq8-admin", true);

        var main = menus.menus().stream().filter(NavigationMenu::main).findFirst().orElseThrow();
        var named = menus.createEmpty("FQ8 " + suffix);
        var earlierCatalog = functions.indexed();
        var definition = new QueryListDefinition(key, "Журналы по запросу",
            new QueryDefinition("select j.id as journalId from Journal j", null, null, null, List.of()),
            null, null, null, Set.of(), QueryProvenance.REGISTERED);
        try {
            var saved = lists.create(definition, true);
            assertThat(earlierCatalog.find(functionKey)).isEmpty();
            var function = functions.indexed().find(functionKey).orElseThrow();
            assertThat(function.kind()).isEqualTo(NavigationFunction.Kind.QUERY_LIST);
            assertThat(function.entityClass()).isNull();
            assertThat(menus.functions()).anyMatch(found -> found.function().equals(function));
            var mainMenu = place(main.id(), placement, functionKey);
            var namedMenu = place(named.id(), placement, functionKey);
            assertThat(menuCommands.canPreviewQueryList(function, reader)).isTrue();
            assertThat(menuCommands.canPreviewQueryList(function, denied)).isFalse();

            login(reader, false);
            assertThat(shows(mainMenu, functionKey)).isTrue();
            assertThat(shows(namedMenu, functionKey)).isTrue();
            var ui = mock(UI.class);
            UI.setCurrent(ui);
            assertThat(menuCommands.execute(function, null).actionable()).isTrue();
            verify(ui).navigate(QueryListView.class, new RouteParameters("key", key));

            login(denied, false);
            assertThat(shows(mainMenu, functionKey)).isFalse();
            assertThat(shows(namedMenu, functionKey)).isFalse();
            assertThat(menuCommands.execute(function, null).actionable()).isFalse();

            login("fq8-admin", true);
            var disabled = lists.setEnabled(key, saved.version(), false);
            assertStale(main.id(), named.id(), functionKey);
            var enabled = lists.setEnabled(key, disabled.version(), true);
            assertThat(functions.find(functionKey)).contains(function);
            lists.delete(key, enabled.version());
            assertStale(main.id(), named.id(), functionKey);
        } finally {
            login("fq8-admin", true);
            var current = menus.structure(main.id());
            var remaining = current.entries().stream().filter(entry -> !entry.id().equals(placement)).toList();
            var sections = current.sections().stream().filter(section -> !section.id().equals(placement)).toList();
            if (remaining.size() != current.entries().size()) {
                menus.save(main.id(), current.version(), new NavigationStructure(main.id(), current.version(),
                    current.seededAt(), sections, remaining));
            }
            menus.delete(named.id(), menus.structure(named.id()).version());
            lists.administrativeEntries().stream().filter(entry -> entry.key().equals(key))
                .findFirst().ifPresent(entry -> lists.delete(key, entry.version()));
        }
    }

    private NavigationStructure place(long menuId, String id, String functionKey) {
        var current = menus.structure(menuId);
        var sections = new ArrayList<>(current.sections());
        sections.add(new NavigationStructure.Section(id, null, "Списки по запросу", "", sections.size(), null));
        var entries = new ArrayList<>(current.entries());
        entries.add(new NavigationStructure.Entry(id, id, functionKey, null, 0, NavigationImportance.NORMAL));
        return menus.save(menuId, current.version(), new NavigationStructure(menuId, current.version(),
            current.seededAt(), sections, entries));
    }

    private boolean shows(NavigationStructure structure, String functionKey) {
        var catalog = NavigationCatalog.build(structure, functions.indexed());
        return new NavigationResolver().resolve(catalog, item -> menuCommands.decide(item.function()).actionable())
            .roots().stream().flatMap(root -> root.groupsRecursive().stream())
            .flatMap(group -> group.items().stream()).anyMatch(item -> item.function().key().equals(functionKey));
    }

    private void assertStale(long primary, long named, String key) {
        assertThat(functions.find(key)).isEmpty();
        assertThat(menus.structure(primary).entries()).anyMatch(entry -> entry.functionKey().equals(key) && entry.stale());
        assertThat(menus.structure(named).entries()).anyMatch(entry -> entry.functionKey().equals(key) && entry.stale());
    }

    @Test
    void routeReadsEachUsersRowsAndRestoresSavedViewWithoutUtr() {
        UI.setCurrent(new UI());
        assertThat(context.getBeansOfType(org.ipro.utr.UtrFileStore.class)).isEmpty();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String alice = "fq6-alice-" + suffix;
        String bob = "fq6-bob-" + suffix;
        String code = "FQ6-" + suffix;
        long aliceJournal = journal(code + "-a");
        grant(alice, aliceJournal);
        grant(bob, journal(code + "-b"));
        RlsTestFixture.grantC5EntityOperations(grants, alice, org.ip.model.GridFormView.class);
        var definition = new QueryListDefinition("journals." + suffix, "Журналы",
                new QueryDefinition("select j.id as journalId, j.code as code from Journal j where j.code like '"
                        + code + "%'", null, null, null, List.of()), null, null,
                new QueryListDefinition.CommandObject(Journal.class.getName(), "journalId"), Set.of(Command.OPEN),
                QueryProvenance.REGISTERED);
        login("fq6-admin", true);
        var saved = lists.create(definition, true);
        try {
            login(alice, false);
            var aliceForm = enter(definition.key());
            assertThat(codes(aliceForm)).containsExactly(code + "-a");
            assertThat(actions.forList(saved).decide(Command.OPEN, aliceJournal).actionable()).isTrue();
            assertThat(referenceLink(aliceForm)).isInstanceOf(Button.class);
            var layout = new GridViewState(List.of(), List.of(), null, null, Map.of("query.sort", "code:DESC"));
            var personal = views.create(aliceForm.formKey(), "Мой вид", false, layout);
            views.setDefaultView(aliceForm.formKey(), personal.id());
            assertThat(enter(definition.key()).panel().captureState().toViewState().options())
                    .containsEntry("query.sort", "code:DESC");

            login(bob, false);
            assertThat(actions.forList(saved).execute(Command.OPEN, aliceJournal, () -> { }).blockReason())
                    .contains("Запись недоступна");
            assertThat(codes(enter(definition.key()))).containsExactly(code + "-b");
            assertThat(views.views(aliceForm.formKey())).isEmpty();
            assertThat(views.defaultViewId(aliceForm.formKey())).isEmpty();
        } finally {
            login("fq6-admin", true);
            lists.delete(definition.key(), saved.version());
        }
    }

    private static Object referenceLink(ProjectionListForm form) {
        var grid = form.panel().grid().orElseThrow().getGrid();
        var row = grid.getDataProvider().fetch(new Query<>()).findFirst().orElseThrow();
        var renderer = (ComponentRenderer<?, Tuple>) grid.getColumnByKey("journalId").getRenderer();
        return renderer.createComponent(row).getChildren().findFirst().orElseThrow();
    }

    private ProjectionListForm enter(String key) {
        var route = context.getAutowireCapableBeanFactory().createBean(QueryListView.class);
        var event = mock(BeforeEnterEvent.class);
        when(event.getRouteParameters()).thenReturn(new RouteParameters("key", key));
        route.beforeEnter(event);
        var form = (ProjectionListForm) route.getChildren().findFirst().orElseThrow();
        form.panel().open();
        return form;
    }

    private static List<String> codes(ProjectionListForm form) {
        return form.panel().grid().orElseThrow().getGrid().getDataProvider().fetch(new Query<>())
                .map(row -> row.get("code", String.class)).toList();
    }

    private long journal(String code) {
        return RlsTestFixture.callAsSuperuser(grants, () -> {
            var journal = new Journal();
            journal.setCode(code);
            journal.setName(code);
            return journals.saveAndFlush(journal).getId();
        });
    }

    private void grant(String user, long journalId) {
        RlsTestFixture.grantC5EntityRead(grants, user, Journal.class, org.ip.model.GridFormView.class);
        RlsTestFixture.callAsSuperuser(grants, () -> {
            var grant = new AccessGrant();
            grant.setSubjectType(AccessGrant.SubjectType.USER);
            grant.setSubjectKey(user);
            grant.setDimension("JOURNAL");
            grant.setDimensionValueId(journalId);
            grant.setCanRead(true);
            return grants.saveAndFlush(grant);
        });
    }

    private static void login(String user, boolean admin) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, admin ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN")) : List.of()));
    }
}
