package org.ipro.navigation.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.ipro.crud.BaseEntity;

@Entity
@Table(name = "navigation_structure")
public class NavigationStructureEntity extends BaseEntity {
    @Column(name = "singleton_key", nullable = false, unique = true, updatable = false)
    private int singletonKey = 1;

    @Column(name = "seeded_at", nullable = false, updatable = false)
    private Instant seededAt;

    @Column(name = "change_sequence", nullable = false)
    private long changeSequence;

    protected NavigationStructureEntity() {}

    public NavigationStructureEntity(Instant seededAt) {
        this.seededAt = seededAt;
    }

    public Instant getSeededAt() { return seededAt; }

    public void touch() { changeSequence++; }
}
