package org.ipro.rls;

import org.hibernate.event.spi.PreDeleteEvent;
import org.hibernate.event.spi.PreDeleteEventListener;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;

/**
 * Final fail-closed boundary for entity writes, including commit-time dirty checking.
 *
 * <p>The dimension registry belongs to this listener instance, not to a static bridge: a
 * session factory may only be guarded by the registry of the context that created it. Without
 * that, an armed context would decide for every later context in the same JVM.</p>
 */
public final class RlsWriteEnforcementListener
        implements PreInsertEventListener, PreUpdateEventListener, PreDeleteEventListener {

    private final RlsDimensionRegistry dimensionRegistry;

    public RlsWriteEnforcementListener(RlsDimensionRegistry dimensionRegistry) {
        this.dimensionRegistry = dimensionRegistry;
    }

    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        RlsWriteGuardBridge.requireAuthorized(dimensionRegistry, event.getEntity(),
            RlsWriteAuthorization.Operation.WRITE);
        return false;
    }

    @Override
    public boolean onPreUpdate(PreUpdateEvent event) {
        RlsWriteGuardBridge.requireAuthorized(dimensionRegistry, event.getEntity(),
            RlsWriteAuthorization.Operation.WRITE);
        return false;
    }

    @Override
    public boolean onPreDelete(PreDeleteEvent event) {
        RlsWriteGuardBridge.requireAuthorized(dimensionRegistry, event.getEntity(),
            RlsWriteAuthorization.Operation.DELETE);
        return false;
    }
}
