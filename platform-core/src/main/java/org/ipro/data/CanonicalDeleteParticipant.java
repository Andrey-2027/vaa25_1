package org.ipro.data;

import org.ipro.identity.IdentifiableEntity;

/**
 * Server-side participant in canonical deletion of an aggregate root.
 *
 * <p>Participants run after the root's delete policy and aggregate lock/version check, inside
 * the same transaction, before reference validation and root removal. Implementations must
 * limit cleanup to rows owned by this exact root and must not expose those rows to the caller.</p>
 */
public interface CanonicalDeleteParticipant {
    boolean supports(Class<?> rootType);

    void beforeDelete(IdentifiableEntity<Long> lockedRoot);
}
