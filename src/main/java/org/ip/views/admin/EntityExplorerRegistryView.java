package org.ip.views.admin;

import com.vaadin.flow.component.html.Div;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import org.ipro.vaadin.explorer.ui.EntityExplorerRegistryActions;
import org.ipro.vaadin.explorer.ui.EntityExplorerRegistryPanel;
import org.ipro.vaadin.explorer.ui.EntityExplorerRegistryState;

public class EntityExplorerRegistryView extends Div {
    private EntityExplorerRegistryPanel panel;
    private EntityExplorerRegistryState state;
    private ExplorerSnapshot snapshot;

    void show(ExplorerSnapshot next, EntityExplorerRegistryState selection, EntityExplorerRegistryActions actions) {
        if (panel == null || state != selection) {
            removeAll();
            state = selection;
            panel = new EntityExplorerRegistryPanel(next, selection, actions);
            add(panel);
        } else if (snapshot != next) {
            panel.setSnapshot(next);
        }
        snapshot = next;
    }

    void clear() {
        removeAll();
        panel = null;
        state = null;
        snapshot = null;
    }
}
