package org.ipro.navigation.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.ipro.crud.BaseEntity;

@Entity(name = "NavigationAssignmentStore")
@Table(name = "navigation_assignment", uniqueConstraints =
    @UniqueConstraint(name = "navigation_assignment_user_uk", columnNames = "user_key"))
public class NavigationAssignmentEntity extends BaseEntity {
    @Column(name = "user_key", nullable = false, length = 190)
    private String userKey;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false,
        foreignKey = @ForeignKey(name = "navigation_assignment_profile_fk"))
    private NavigationProfileEntity profile;

    protected NavigationAssignmentEntity() {}
    public NavigationAssignmentEntity(String userKey, NavigationProfileEntity profile) {
        this.userKey = userKey;
        this.profile = profile;
    }
    public NavigationProfileEntity getProfile() { return profile; }
    public void assign(NavigationProfileEntity profile) { this.profile = profile; }
}
