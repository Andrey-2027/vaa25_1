package org.ipro.navigation.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.ipro.crud.BaseEntity;

@Entity
@Table(name = "navigation_known_function")
public class NavigationKnownFunctionEntity extends BaseEntity {
    @Column(name = "function_key", nullable = false, unique = true, length = 190, updatable = false)
    private String key;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    protected NavigationKnownFunctionEntity() {}

    public NavigationKnownFunctionEntity(String key, Instant firstSeenAt) {
        this.key = key;
        this.firstSeenAt = firstSeenAt;
    }

    public String getKey() { return key; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
}
