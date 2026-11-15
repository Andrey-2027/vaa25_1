package org.ip.views.query;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.RouteParameters;
import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.repository.JournalRepository;
import org.ipro.filtergrid.projection.QueryProvenance;
import org.ipro.metadata.GridViewState;
import org.ipro.query.list.QueryListDefinition;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "app.utr.enabled=false")
class QueryListRouteIT {
    @MockitoBean DataInitializer dataInitializer;
    @Autowired ApplicationContext context;
    @Autowired QueryListRegistry lists;
    @Autowired QueryListViews views;
    @Autowired AccessGrantRepository grants;
    @Autowired JournalRepository journals;

    @AfterEach
    void clearContext() {
        UI.setCurrent(null);
        SecurityContextHolder.clearContext();
    }

    @Test
    void routeReadsEachUsersRowsAndRestoresSavedViewWithoutUtr() {
        UI.setCurrent(new UI());
        assertThat(context.getBeansOfType(org.ipro.utr.UtrFileStore.class)).isEmpty();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String alice = "fq6-alice-" + suffix;
        String bob = "fq6-bob-" + suffix;
        String code = "FQ6-" + suffix;
        grant(alice, journal(code + "-a"));
        grant(bob, journal(code + "-b"));
        RlsTestFixture.grantC5EntityOperations(grants, alice, org.ip.model.GridFormView.class);
        var definition = new QueryListDefinition("journals." + suffix, "Журналы",
                new QueryDefinition("select j.id as journalId, j.code as code from Journal j where j.code like '"
                        + code + "%'", null, null, null, List.of()), null, null, null, Set.of(),
                QueryProvenance.REGISTERED);
        login("fq6-admin", true);
        var saved = lists.create(definition, true);
        try {
            login(alice, false);
            var aliceForm = enter(definition.key());
            assertThat(codes(aliceForm)).containsExactly(code + "-a");
            var layout = new GridViewState(List.of(), List.of(), null, null, Map.of("query.sort", "code:DESC"));
            var personal = views.create(aliceForm.formKey(), "Мой вид", false, layout);
            views.setDefaultView(aliceForm.formKey(), personal.id());
            assertThat(enter(definition.key()).panel().captureState().toViewState().options())
                    .containsEntry("query.sort", "code:DESC");

            login(bob, false);
            assertThat(codes(enter(definition.key()))).containsExactly(code + "-b");
            assertThat(views.views(aliceForm.formKey())).isEmpty();
            assertThat(views.defaultViewId(aliceForm.formKey())).isEmpty();
        } finally {
            login("fq6-admin", true);
            lists.delete(definition.key(), saved.version());
        }
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
