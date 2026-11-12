package org.ip.views.admin;

import com.vaadin.flow.spring.annotation.UIScope;
import org.ipro.vaadin.explorer.ui.EntityExplorerRegistryState;
import org.springframework.stereotype.Component;

@Component
@UIScope
public class EntityExplorerUiSession {
    private EntityExplorerRegistryState registry = new EntityExplorerRegistryState();

    EntityExplorerRegistryState registry() {
        return registry;
    }

    void reset() {
        registry = new EntityExplorerRegistryState();
    }
}
