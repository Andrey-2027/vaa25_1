package org.ipro.crud;

import org.ipro.identity.IdentifiableEntity;

/** Server-side adapter that applies one contribution inside the root's transaction. */
public interface AggregateSaveContributionParticipant {
    boolean supports(Class<?> rootType, AggregateSaveContribution contribution);

    void apply(IdentifiableEntity<Long> savedRoot, AggregateSaveContribution contribution,
               AggregateSaveParticipantContext context);
}
