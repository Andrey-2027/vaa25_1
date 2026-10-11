package org.ipro.navigation.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.ipro.crud.BaseEntity;

/** INTERNAL_STORE. Only the navigation owner performs mutations. */
@Entity(name = "NavigationProfileStore")
@Table(name = "navigation_profile", uniqueConstraints =
    @UniqueConstraint(name = "navigation_profile_name_uk", columnNames = "normalized_name"))
public class NavigationProfileEntity extends BaseEntity {
    @Column(nullable = false, length = 120)
    private String name;
    @Column(name = "normalized_name", nullable = false, length = 120)
    private String normalizedName;
    @Column(name = "change_sequence", nullable = false)
    private long changeSequence;

    protected NavigationProfileEntity() {}
    public NavigationProfileEntity(String name, String normalizedName) { rename(name, normalizedName); }
    public String getName() { return name; }
    public void rename(String name, String normalizedName) {
        this.name = name;
        this.normalizedName = normalizedName;
        touch();
    }
    /** Makes even an overrides-only or empty reset operation increment the aggregate version. */
    public void touch() { changeSequence++; }
}
