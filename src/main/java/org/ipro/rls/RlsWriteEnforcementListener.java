package org.ipro.rls;

import org.hibernate.event.spi.PreDeleteEvent;
import org.hibernate.event.spi.PreDeleteEventListener;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;

/** Final fail-closed boundary for entity writes, including commit-time dirty checking. */
public final class RlsWriteEnforcementListener
        implements PreInsertEventListener, PreUpdateEventListener, PreDeleteEventListener {

    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        RlsWriteGuardBridge.requireAuthorized(event.getEntity(), RlsWriteAuthorization.Operation.WRITE);
        return false;
    }

    @Override
    public boolean onPreUpdate(PreUpdateEvent event) {
        RlsWriteGuardBridge.requireAuthorized(event.getEntity(), RlsWriteAuthorization.Operation.WRITE);
        return false;
    }

    @Override
    public boolean onPreDelete(PreDeleteEvent event) {
        RlsWriteGuardBridge.requireAuthorized(event.getEntity(), RlsWriteAuthorization.Operation.DELETE);
        return false;
    }
}
