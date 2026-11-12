package org.ip.views.admin;

import com.vaadin.flow.spring.annotation.UIScope;
import org.ip.views.workspace.Workspace;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.RecipeDescriptor;
import org.ipro.metadata.annotation.EntityKind;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.Optional;

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
        workspace.open(EntityDraftEditorView.class, ENTRY_ID, "Черновик · Выбор вида", view -> {
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

    static Optional<RecipeDescriptor> creationRecipe(EntityKind kind) {
        return RecipeCatalog.descriptors().stream()
            .filter(recipe -> recipe.kind().equals(kind.name()) && recipe.draftSupported() && recipe.generatorImplemented())
            .filter(recipe -> !RecipeCatalog.isRlsRegisterRecipe(recipe.ref()))
            .max(Comparator.comparingInt(recipe -> recipe.ref().version()));
    }

    String creationRefusal(EntityKind kind) {
        if (!access.allows()) return "Редактор черновика недоступен";
        if (creationRecipe(kind).isPresent()) return "";
        return RecipeCatalog.descriptors().stream().filter(recipe -> recipe.kind().equals(kind.name()))
            .flatMap(recipe -> recipe.unavailableReasons().stream()).findFirst()
            .orElse("Для этого вида пока нет рецепта создания");
    }

    void open(EntityKind kind) {
        if (!access.allows()) return;
        creationRecipe(kind).ifPresent(recipe -> {
            open();
            if (editor != null) editor.requestNewDraft(recipe);
        });
    }
}
