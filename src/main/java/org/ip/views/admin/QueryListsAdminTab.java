package org.ip.views.admin;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.spring.annotation.SpringComponent;
import jakarta.persistence.EntityManagerFactory;
import org.ip.views.query.QueryListView;
import org.ipro.crud.EntityLookup;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.query.QueryBuilderMetadataCatalog;
import org.ipro.query.QuerySnapshotService;
import org.ipro.query.ReportPreviewService;
import org.ipro.query.ReportProjectionReads;
import org.ipro.query.ReportQueryAssemblyService;
import org.ipro.query.list.QueryListRegistry;
import org.ipro.query.ui.admin.QueryListAdminEnvironment;
import org.ipro.query.ui.admin.QueryListAdminPanel;
import org.ipro.query.ui.editor.QueryEditorAnalysisService;
import org.ipro.query.ui.editor.QueryMetadataCatalogService;
import org.ipro.query.ui.editor.ReportQueryEditor;
import org.ipro.query.ui.grid.QueryGridEnvironment;
import org.ipro.query.ui.selection.EntitySelection;
import org.springframework.context.annotation.Scope;

import java.util.HashMap;
import java.util.Map;

/**
 * Вкладка «Списки по запросу» в «Администрировании» (FQ-6, п. 8). Сам экран живёт в
 * {@code platform-query-vaadin}; здесь только сборка его окружения из бинов приложения. Право на
 * запись проверяет реестр на сервере, вкладку видит администратор, как и остальные вкладки.
 */
@SpringComponent
@Scope("prototype")
public class QueryListsAdminTab extends VerticalLayout {
    private final QueryListAdminPanel panel;

    public QueryListsAdminTab(QueryListRegistry registry, QueryGridEnvironment grid, EntitySelection selection,
                              ReportProjectionReads reads, QueryEditorAnalysisService analysis,
                              QueryMetadataCatalogService catalogs, ReportPreviewService previews,
                              EntityLookup lookup, ReportQueryAssemblyService assembly,
                              QuerySnapshotService snapshots, QueryBuilderMetadataCatalog visualCatalog,
                              EntityManagerFactory entityManagerFactory, MetadataResolver metadata) {
        Map<String, String> titles = new HashMap<>();
        for (var entity : entityManagerFactory.getMetamodel().getEntities()) {
            Class<?> type = entity.getJavaType();
            String title = metadata.find(type).map(EntityMetadataInfo::getItemFormTitle).orElse(type.getSimpleName());
            titles.put(type.getName(), title);
            titles.put(entity.getName(), title);
        }
        var environment = new QueryListAdminEnvironment(registry, grid, () -> {
            var editor = new ReportQueryEditor(analysis, catalogs, previews, lookup, selection, assembly, snapshots);
            editor.setQueryConstructorCatalog(visualCatalog);
            return editor;
        }, selection, name -> titles.getOrDefault(name, name.substring(name.lastIndexOf('.') + 1)),
                QueryListView::listUrl, reads.inMemoryLimit());
        this.panel = new QueryListAdminPanel(environment);
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        setMinHeight("0");
        add(panel);
        setFlexGrow(1, panel);
    }

    /** Перечитывает перечень при показе вкладки. */
    public void refresh() {
        panel.refresh();
    }

    public boolean isDirty() {
        return panel.isDirty();
    }

    public String closeConfirmMessage() {
        return panel.closeConfirmMessage();
    }

    public boolean saveChanges() {
        return panel.saveChanges();
    }

    /** Выполняет {@code proceed}; при несохранённых изменениях сначала спрашивает подтверждение. */
    public void confirmLeave(Runnable proceed) {
        panel.confirmLeave(proceed);
    }
}
