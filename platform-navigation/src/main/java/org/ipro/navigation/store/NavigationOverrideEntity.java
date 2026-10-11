package org.ipro.navigation.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.ipro.crud.BaseEntity;
import org.ipro.navigation.NavigationVisibility;

@Entity(name = "NavigationOverrideStore")
@Table(name = "navigation_override", uniqueConstraints = @UniqueConstraint(
    name = "navigation_override_target_uk", columnNames = {"profile_id", "target_key"}))
public class NavigationOverrideEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false,
        foreignKey = @ForeignKey(name = "navigation_override_profile_fk"))
    private NavigationProfileEntity profile;
    @Column(name = "target_key", nullable = false, length = 190)
    private String targetKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private NavigationVisibility visibility;

    protected NavigationOverrideEntity() {}
    public NavigationOverrideEntity(NavigationProfileEntity profile, String key, NavigationVisibility visibility) {
        this.profile = profile;
        this.targetKey = key;
        this.visibility = visibility;
    }
    public String getTargetKey() { return targetKey; }
    public NavigationVisibility getVisibility() { return visibility; }
}
