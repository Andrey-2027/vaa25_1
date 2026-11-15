package org.ip.views.admin;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.grid.Grid;
import org.ip.config.DataInitializer;
import org.ipro.filtergrid.projection.QueryProvenance;
import org.ipro.query.list.QueryListDefinition;
import org.ipro.query.list.QueryListRegistry;
import org.ipro.query.model.QueryDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FQ-6 п. 8: вкладка «Списки по запросу» собирается из бинов приложения и читает реестр с настоящим компилятором. */
@SpringBootTest(properties = "app.utr.enabled=false")
class QueryListAdminIT {
    @MockitoBean DataInitializer dataInitializer;
    @Autowired ApplicationContext context;
    @Autowired QueryListRegistry lists;
    private final UI ui = new UI();

    @AfterEach
    void clearContext() {
        UI.setCurrent(null);
        SecurityContextHolder.clearContext();
    }

    @Test
    void theTabListsASavedDefinitionWithTheEntitiesItsQueryReads() {
        UI.setCurrent(ui);
        login("fq6-admin", true);
        String key = "journals." + UUID.randomUUID().toString().substring(0, 8);
        var definition = new QueryListDefinition(key, "Журналы",
                new QueryDefinition("select j.id as journalId, j.code as code from Journal j", null, null, null,
                        List.of()), null, null, null, Set.of(), QueryProvenance.REGISTERED);

        var check = lists.check(definition);
        assertThat(check.ok()).as(String.valueOf(check.diagnostic())).isTrue();
        assertThat(check.columns()).containsExactly("journalId", "code");
        assertThat(check.entities()).anyMatch(name -> name.endsWith("Journal"));

        var saved = lists.create(definition, true);
        try {
            var tab = context.getAutowireCapableBeanFactory().createBean(QueryListsAdminTab.class);
            tab.refresh();
            var panel = ReflectionTestUtils.getField(tab, "panel");
            @SuppressWarnings("unchecked")
            var grid = (Grid<QueryListRegistry.Entry>) ReflectionTestUtils.getField(panel, "grid");
            var entry = grid.getListDataView().getItems().filter(item -> item.key().equals(key)).findFirst()
                    .orElseThrow();
            assertThat(entry.diagnostic()).isNull();
            assertThat(entry.entities()).anyMatch(name -> name.endsWith("Journal"));
            assertThat(tab.isDirty()).isFalse();
        } finally {
            lists.delete(key, saved.version());
        }
    }

    @Test
    void withoutTheAdministrationRightTheTabShowsNoListing() {
        UI.setCurrent(ui);
        login("fq6-clerk", false);
        var tab = context.getAutowireCapableBeanFactory().createBean(QueryListsAdminTab.class);
        tab.refresh();
        var panel = ReflectionTestUtils.getField(tab, "panel");
        @SuppressWarnings("unchecked")
        var grid = (Grid<QueryListRegistry.Entry>) ReflectionTestUtils.getField(panel, "grid");
        assertThat(grid.isVisible()).isFalse();
        assertThat(grid.getListDataView().getItemCount()).isZero();
    }

    private static void login(String user, boolean admin) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, admin ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN")) : List.of()));
    }
}
