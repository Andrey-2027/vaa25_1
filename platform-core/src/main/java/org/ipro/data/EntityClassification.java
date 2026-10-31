package org.ipro.data;

import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.annotation.EntityKind;
import java.util.Objects;

/** Immutable semantic and identity facts. Exposure and write policy are independent. */
public record EntityClassification(EntityKind kind, FactOrigin origin, String symbol,
                                   boolean interned, FactOrigin identityOrigin, String identitySymbol,
                                   boolean recorderOwned) {
    public EntityClassification {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(symbol);
        Objects.requireNonNull(identityOrigin);
        Objects.requireNonNull(identitySymbol);
        if (kind == EntityKind.AUTO) throw new IllegalArgumentException("AUTO is not an effective kind");
    }
}
