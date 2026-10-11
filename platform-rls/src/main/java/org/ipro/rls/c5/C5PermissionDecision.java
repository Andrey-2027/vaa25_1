package org.ipro.rls.c5;

import java.util.List;
import java.util.Objects;

/**
 * One C5 decision: allow or deny, with a stable reason, the checked key and operation, and the
 * source that allowed it (ADR-0017 §10, §11).
 *
 * <p>{@code sources} names the grant that was sufficient for an allow — {@code "прямой"} for the
 * subject's own grant, {@code "роль: <name>"} for a role grant — in the same wording as
 * {@code AccessService} and the grant admin, so the shown and enforced decisions cannot disagree.
 * The decision is short-circuited at the first allowing source, therefore the list is sufficient,
 * not exhaustive; the full set of sources belongs to the admin path, not to enforcement.</p>
 *
 * <p>A denial carries no keys of other subjects and no hidden data: only the value of
     * {@link C5DecisionReason} and a safe label from declared UI metadata may be exposed to a user.
     * Technical keys and operations belong to server logs and authorized administrative details.</p>
 */
public record C5PermissionDecision(boolean allowed,
                                   C5DecisionReason reason,
                                   String key,
                                   C5Operation operation,
                                   List<String> sources) {

    public C5PermissionDecision {
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        sources = sources == null ? List.of() : List.copyOf(sources);
        if (allowed != (reason == C5DecisionReason.PERMITTED)) {
            throw new IllegalArgumentException("C5 decision for " + key + "/" + operation
                + " must be allowed exactly when the reason is PERMITTED: " + reason);
        }
        if (!allowed && !sources.isEmpty()) {
            throw new IllegalArgumentException("Denied C5 decision for " + key
                + " must not carry grant sources");
        }
    }

    public static C5PermissionDecision permitted(String key, C5Operation operation, List<String> sources) {
        return new C5PermissionDecision(true, C5DecisionReason.PERMITTED, key, operation, sources);
    }

    public static C5PermissionDecision denied(C5DecisionReason reason, String key, C5Operation operation) {
        if (reason == C5DecisionReason.PERMITTED) {
            throw new IllegalArgumentException("Denied C5 decision cannot use reason PERMITTED");
        }
        return new C5PermissionDecision(false, reason, key, operation, List.of());
    }

    /** Safe user message of this decision. */
    public String message() {
        return reason.message();
    }
}
