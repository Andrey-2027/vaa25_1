package org.ipro.navigation.store;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.ipro.crud.BaseEntity;

import java.time.Instant;

@Entity
@Table(name = "navigation_menu", uniqueConstraints = {
    @UniqueConstraint(name = "navigation_menu_name_uk", columnNames = "normalized_name"),
    @UniqueConstraint(name = "navigation_menu_main_uk", columnNames = "main_key")
}, check = @CheckConstraint(name = "navigation_menu_main_ck", constraint = "main_key = 1"))
public class NavigationMenuEntity extends BaseEntity {
    @Column(nullable = false, length = 120)
    private String name;
    @Column(name = "normalized_name", nullable = false, length = 120)
    private String normalizedName;
    @Column(name = "main_key", updatable = false)
    private Integer mainKey;
    @Column(nullable = false)
    private int position;
    @Column(name = "seeded_at", nullable = false, updatable = false)
    private Instant seededAt;
    @Column(name = "change_sequence", nullable = false)
    private long changeSequence;

    protected NavigationMenuEntity() {}

    public NavigationMenuEntity(String name, String normalizedName, boolean main, int position, Instant seededAt) {
        rename(name, normalizedName);
        this.mainKey = main ? 1 : null;
        this.position = position;
        this.seededAt = seededAt;
    }

    public String getName() { return name; }
    public boolean isMain() { return mainKey != null; }
    public int getPosition() { return position; }
    public Instant getSeededAt() { return seededAt; }

    public void rename(String name, String normalizedName) {
        this.name = name;
        this.normalizedName = normalizedName;
        touch();
    }

    public void touch() { changeSequence++; }
}
