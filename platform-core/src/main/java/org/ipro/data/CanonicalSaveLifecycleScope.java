package org.ipro.data;

import org.ipro.events.EventContext;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.lifecycle.EntitySaveContext;

/** Read-only evidence of the canonical executor's current in-transaction onSave callback. */
public final class CanonicalSaveLifecycleScope implements AutoCloseable {
    private static final ThreadLocal<CanonicalSaveLifecycleScope> CURRENT = new ThreadLocal<>();
    private final IdentifiableEntity<?> entity;
    private final EventContext context;
    private final Long originalVersion;
    private final CanonicalSaveLifecycleScope previous;

    private CanonicalSaveLifecycleScope(IdentifiableEntity<?> entity, EventContext context, Long originalVersion) {
        this.entity = entity;
        this.context = context;
        this.originalVersion = originalVersion;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static CanonicalSaveLifecycleScope open(IdentifiableEntity<?> entity, EventContext context) {
        return open(entity, context, null);
    }

    static CanonicalSaveLifecycleScope open(IdentifiableEntity<?> entity, EventContext context, Long originalVersion) {
        return new CanonicalSaveLifecycleScope(entity, context, originalVersion);
    }

    /** Opaque token; consumers can inspect an issued callback, but cannot open one. */
    public static Object tokenFor(EntitySaveContext<?> context) {
        CanonicalSaveLifecycleScope current = CURRENT.get();
        return current != null && current.entity == context.entity()
            && current.context == context.eventContext() ? current : null;
    }

    public static boolean requiresVersionAdvance(EntitySaveContext<?> context, Long currentVersion) {
        CanonicalSaveLifecycleScope current = CURRENT.get();
        return tokenFor(context) != null && current.originalVersion != null
            && current.originalVersion.equals(currentVersion == null ? 0L : currentVersion);
    }

    @Override
    public void close() {
        if (CURRENT.get() != this) {
            throw new IllegalStateException("Canonical save lifecycle scopes must close in order on their opening thread");
        }
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
