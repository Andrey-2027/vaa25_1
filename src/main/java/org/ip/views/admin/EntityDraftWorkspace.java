package org.ip.views.admin;

import com.vaadin.flow.spring.annotation.UIScope;
import org.ip.views.workspace.Workspace;
import org.springframework.stereotype.Component;

/** UI-owned host adapter; entry identity never depends on a draft name or UUID. */
@Component
@UIScope
public class EntityDraftWorkspace {
    public static final String ENTRY_ID = "entity-draft";
    private final Workspace workspace;
    private final EntityDraftAccess access;
    private EntityDraftEditorView editor;
    private org.springframework.beans.factory.ObjectProvider<EntityDraftProjectCatalog> catalogs;
    public EntityDraftWorkspace(Workspace workspace, EntityDraftAccess access) {
        this.workspace = workspace; this.access = access;
        workspace.addActiveEntryListener(id -> {
            if (ENTRY_ID.equals(id) && editor != null) editor.activate();
        });
        workspace.addEntryClosedListener(id -> { if (ENTRY_ID.equals(id)) editor = null; });
    }
    @org.springframework.beans.factory.annotation.Autowired
    public EntityDraftWorkspace(Workspace workspace, EntityDraftAccess access,
            org.springframework.beans.factory.ObjectProvider<EntityDraftProjectCatalog> catalogs) {
        this(workspace, access); this.catalogs = catalogs;
    }
    public void open() {
        if (!access.allows()) { if (editor != null) editor.activate(); return; }
        workspace.open(EntityDraftEditorView.class, ENTRY_ID, "Черновик · Новый справочник", view -> {
            editor = view;
            if (catalogs != null) {
                view.setCatalogSource(() -> catalogs.getObject().current());
                view.setCatalogRefresh(() -> catalogs.getObject().refresh());
            }
            view.initialize(() -> {
                workspace.updateTitle(ENTRY_ID, view.tabTitle());
                workspace.refreshUnloadGuard();
            });
        });
        if (editor != null) {
            editor.activate();
            workspace.updateTitle(ENTRY_ID, editor.tabTitle());
        }
    }
}
