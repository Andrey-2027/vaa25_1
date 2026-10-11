package org.ipro.crud;

import org.ipro.events.AggregateSection;
import org.ipro.identity.IdentifiableEntity;

import java.util.List;

/** Optional server-side payload attached to a canonical aggregate save. */
public interface AggregateSaveContribution {
    /** Stable key within one save, used to reject duplicate contributions. */
    String key();

    /** Rows exposed to aggregate lifecycle hooks and rollback restoration. */
    default List<AggregateSection> eventSections() {
        return List.of();
    }

    default List<? extends IdentifiableEntity<Long>> rollbackEntities() {
        return eventSections().stream().flatMap(section -> section.rows().stream())
            .filter(IdentifiableEntity.class::isInstance)
            .map(AggregateSaveRollbackState::requireLongIdentity)
            .toList();
    }
}
