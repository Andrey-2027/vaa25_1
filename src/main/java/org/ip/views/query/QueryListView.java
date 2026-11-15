package org.ip.views.query;

import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinServletRequest;
import jakarta.annotation.security.PermitAll;
import org.ipro.query.list.QueryListAdminAccess;
import org.ipro.query.list.QueryListRegistry;
import org.ipro.query.ui.grid.QueryGridEnvironment;
import org.ipro.query.ui.grid.QueryGridSpec;
import org.ipro.query.ui.grid.QueryGridState;
import org.ipro.query.ui.listform.ProjectionListForm;
import org.ipro.query.ui.listform.QueryListOptions;
import org.ipro.query.ui.listform.QueryListViews;

@Route("query-list/:key")
@PageTitle("Список по запросу")
@PermitAll
public class QueryListView extends VerticalLayout implements BeforeEnterObserver {
    private final QueryListRegistry registry;
    private final QueryGridEnvironment environment;
    private final QueryListViews views;
    private final QueryWorkbenchAccess access;
    private final QueryListAdminAccess admin;

    public QueryListView(QueryListRegistry registry, QueryGridEnvironment environment,
                         QueryListViews views, QueryWorkbenchAccess access, QueryListAdminAccess admin) {
        this.registry = registry;
        this.environment = environment;
        this.views = views;
        this.access = access;
        this.admin = admin;
        setSizeFull();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        removeAll();
        if (!access.allows()) {
            add(new Paragraph("Список недоступен"));
            return;
        }
        String key = event.getRouteParameters().get("key").orElse(null);
        var entry = registry.findOpenable(key);
        if (entry.isEmpty()) {
            add(new Paragraph(registry.openingDiagnostic(key).map(message -> "Список требует исправления. " + message)
                    .orElse("Список недоступен")));
            return;
        }
        var definition = entry.get().definition();
        var spec = QueryGridSpec.of(definition.title(), entry.get().launchKey(), definition.query())
                .withMode(definition.mode())
                .withStandardState(QueryGridState.fromViewState(definition.standardView()));
        String url = listUrl(definition.key());
        var form = new ProjectionListForm(environment, spec, definition.key(),
                QueryListOptions.DEFAULT.withViews(views).withLink(() -> url));
        form.panel().setDetailsVisible(admin.isAdministrator());
        addAndExpand(form);
    }

    public static String listUrl(String key) {
        String path = "/query-list/" + key;
        var request = VaadinServletRequest.getCurrent();
        return request == null ? path : java.net.URI.create(request.getRequestURL().toString())
                .resolve(request.getContextPath() + path).toString();
    }
}
