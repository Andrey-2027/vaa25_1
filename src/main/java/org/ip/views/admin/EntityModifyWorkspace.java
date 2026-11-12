package org.ip.views.admin;

import com.vaadin.flow.spring.annotation.UIScope;
import java.util.HashMap;
import java.util.Map;
import org.ip.views.workspace.Workspace;
import org.springframework.stereotype.Component;

/** UI-owned host of the entity-change views: one Workspace entry per class, reused when the same card is opened again. */
@Component
@UIScope
public class EntityModifyWorkspace {
    public static final String ENTRY_PREFIX = "entity-modify:";
    private final Workspace workspace;
    private final EntityModificationService service;
    private final Map<String, EntityModifyView> views = new HashMap<>();

    public EntityModifyWorkspace(Workspace workspace, EntityModificationService service) {
        this.workspace = workspace; this.service = service;
        workspace.addActiveEntryListener(id -> {
            var view = views.get(id);
            if (view != null) view.activate();
        });
        workspace.addEntryClosedListener(views::remove);
    }

    /** True when the card of this class gets the «Изменить» action. */
    public boolean allows(Class<?> type) { return service.canModify(type); }

    String refusal(Class<?> type) { return service.refusalToModify(type); }

    public void open(Class<?> type) {
        if (type == null) return;
        open(new EntityStructureTabTitle(type, type.getSimpleName()));
    }

    void open(EntityStructureTabTitle title) {
        Class<?> type = title.type();
        if (!service.canModify(type)) return;
        String id = ENTRY_PREFIX + type.getName();
        String caption = title.caption("Изменение");
        workspace.open(EntityModifyView.class, id, caption, view -> {
            views.put(id, view);
            view.setStructureTitle(title);
            view.initialize(type, () -> {
                workspace.updateTitle(id, view.tabTitle());
                workspace.refreshUnloadGuard();
            });
        });
        var view = views.get(id);
        if (view != null) {
            view.setStructureTitle(title);
            view.activate();
            workspace.updateTitle(id, view.tabTitle());
            workspace.describeTab(id, title.fullName());
        }
    }
}
