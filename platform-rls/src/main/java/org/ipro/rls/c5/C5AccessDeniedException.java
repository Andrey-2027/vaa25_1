package org.ipro.rls.c5;

import org.springframework.security.access.AccessDeniedException;

/** C5 denial with optional structured data; legacy message-only callers remain supported. */
public final class C5AccessDeniedException extends AccessDeniedException {

    private final C5PermissionDecision decision;
    private final Class<?> entityType;

    public C5AccessDeniedException(String message) {
        super(message);
        this.decision = null;
        this.entityType = null;
    }

    public C5AccessDeniedException(C5PermissionDecision decision) {
        this(decision, null);
    }

    public C5AccessDeniedException(C5PermissionDecision decision, Class<?> entityType) {
        super(java.util.Objects.requireNonNull(decision, "decision").message()
            + ": " + decision.operation() + " " + decision.key());
        if (decision.allowed()) {
            throw new IllegalArgumentException("An access denial cannot carry an allowed decision");
        }
        this.decision = decision;
        this.entityType = entityType;
    }

    public C5PermissionDecision decision() { return decision; }

    public Class<?> entityType() { return entityType; }
}
