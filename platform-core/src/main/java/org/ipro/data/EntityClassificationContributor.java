package org.ipro.data;

import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.annotation.EntityKind;
import java.util.Objects;
import java.util.Optional;

/** Backend SPI. Sources validate descriptors before returning facts; failures propagate. */
public interface EntityClassificationContributor {
    Optional<Contribution> contribute(Class<?> type);

    record Contribution(EntityKind kind, FactOrigin origin, String symbol, boolean recorderOwned) {
        public Contribution {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(symbol);
            if (kind == EntityKind.AUTO || kind == EntityKind.PLAIN)
                throw new IllegalArgumentException("Contribution requires a semantic kind");
        }
    }
}
