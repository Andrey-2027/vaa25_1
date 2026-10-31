package org.ip.views.query;

import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.ipro.crud.EntityLookup;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.query.QueryBuilderMetadataCatalog;
import org.ipro.query.QuerySnapshotService;
import org.ipro.query.ReportPreviewService;
import org.ipro.query.ReportQueryAssemblyService;
import org.ipro.query.model.QuerySource;
import org.ipro.query.ui.editor.QueryDraft;
import org.ipro.query.ui.editor.QueryEditorAnalysisService;
import org.ipro.query.ui.editor.QueryMetadataCatalogService;
import org.ipro.query.ui.editor.ReportQueryEditor;


import java.util.List;

/**
 * T1: самостоятельный экран «Конструктор запросов» — query → table без отчёта.
 *
 * <p>Экран не открывает каталог шаблонов, не сохраняет запрос и не запускает
 * отчётные движки. Он работает с черновиком в памяти и показывает снимок
 * результата: N строк, признак «есть ещё» (N+1) и фильтр, который действует
 * только на уже полученный снимок.</p>
 */
@Route("queries")
@PageTitle("Конструктор запросов")
@PermitAll
public class QueryWorkbenchView extends VerticalLayout implements BeforeEnterObserver {

    private final QueryWorkbenchAccess access;

    public QueryWorkbenchView(QueryWorkbenchAccess access,
                              QueryEditorAnalysisService analysisService,
                              QueryMetadataCatalogService catalogService,
                              ReportPreviewService previewService,
                              EntityLookup lookupService,
                              SelectionFormAssembler selectionFormAssembler,
                              ReportQueryAssemblyService queryAssemblyService,
                              QuerySnapshotService snapshotService,
                              QueryBuilderMetadataCatalog visualCatalog) {
        this.access = access;

        ReportQueryEditor editor = new ReportQueryEditor(analysisService, catalogService, previewService,
                lookupService, new FormEntitySelection(selectionFormAssembler, lookupService),
                queryAssemblyService, snapshotService);
        editor.setQueryConstructorCatalog(visualCatalog);
        editor.open(new QueryDraft("", QuerySource.MANUAL, null, null, List.of()));
        editor.setSizeFull();

        H2 title = new H2("Конструктор запросов");
        title.getStyle().set("margin", "0");
        Paragraph hint = new Paragraph("Снимок до 50 строк (лимит поля «Строк»). Сортировка и фильтр "
                + "действуют на снимок, а не на запрос. Экран ничего не сохраняет: запрос живёт "
                + "только в этой вкладке.");
        hint.getStyle().set("margin", "0");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        hint.getStyle().set("font-size", "var(--lumo-font-size-s)");

        setSizeFull();
        setPadding(true);
        setSpacing(true);
        add(title, hint, editor);
        setFlexGrow(1, editor);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!access.allows()) {
            event.rerouteTo("login");
        }
    }
}
