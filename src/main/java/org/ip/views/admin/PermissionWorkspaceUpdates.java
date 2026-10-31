package org.ip.views.admin;

import com.vaadin.flow.shared.Registration;
import java.util.concurrent.CopyOnWriteArrayList;
import org.ip.model.User;
import org.ip.model.Role;
import org.ip.application.access.PermissionPatchService;
import org.ipro.events.EntityChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Committed-only notifications; attached workspaces unregister on detach. */
@Component
public class PermissionWorkspaceUpdates {
    private final CopyOnWriteArrayList<Runnable> subscribers = new CopyOnWriteArrayList<>();
    public Registration subscribe(Runnable subscriber) {
        subscribers.add(subscriber); return () -> subscribers.remove(subscriber);
    }
    @EventListener public void changed(EntityChangedEvent<?> event) {
        if (event.entity() instanceof User || event.entity() instanceof Role) notifySubscribers();
    }
    @org.springframework.transaction.event.TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void permissions(PermissionPatchService.CommittedChange event) { notifySubscribers(); }
    private void notifySubscribers() { subscribers.forEach(Runnable::run); }
}
