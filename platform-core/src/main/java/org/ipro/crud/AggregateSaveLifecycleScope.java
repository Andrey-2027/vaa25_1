package org.ipro.crud;

import org.ipro.events.EventContext;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.lifecycle.EntitySaveContext;

/**
 * Read-only evidence of the aggregate owner's onSave after root and sections have been persisted.
 *
 * <p>Дисциплина потока и порядка закрытия — общая для crud-scope'ов ({@link CallbackScope},
 * ADR-0026 §3): свидетельство принадлежит потоку, который его открыл, и закрывается только в
 * обратном порядке.</p>
 */
public final class AggregateSaveLifecycleScope extends CallbackScope<AggregateSaveLifecycleScope> {

    private static final ThreadLocal<AggregateSaveLifecycleScope> CURRENT = new ThreadLocal<>();

    private final IdentifiableEntity<?> entity;
    private final EventContext context;
    private final AggregateSaveParticipantContext versionState;

    private AggregateSaveLifecycleScope(IdentifiableEntity<?> entity, EventContext context,
                                       AggregateSaveParticipantContext versionState) {
        super(CURRENT, "Aggregate save lifecycle");
        this.entity = entity;
        this.context = context;
        this.versionState = versionState;
        enter(this);
    }

    static AggregateSaveLifecycleScope open(IdentifiableEntity<?> entity, EventContext context,
                                            AggregateSaveParticipantContext versionState) {
        return new AggregateSaveLifecycleScope(entity, context, versionState);
    }

    /** Opaque token; application-created EntitySaveContext values confer no authority. */
    public static Object tokenFor(EntitySaveContext<?> context) {
        AggregateSaveLifecycleScope current = CURRENT.get();
        return current != null && current.entity == context.entity()
            && current.context == context.eventContext() ? current : null;
    }

    /** A register-only aggregate update must participate in the owner's optimistic version. */
    public static boolean requiresVersionAdvance(EntitySaveContext<?> context, Long currentVersion) {
        AggregateSaveLifecycleScope current = CURRENT.get();
        return tokenFor(context) != null && !current.versionState.creating()
            && !current.versionState.versionAdvanceRequested()
            && current.versionState.originalVersion().equals(currentVersion == null ? 0L : currentVersion);
    }
}
