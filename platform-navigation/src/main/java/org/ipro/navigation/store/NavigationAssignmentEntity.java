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
    @JoinColumn(name = "menu_id", nullable = false,
        foreignKey = @ForeignKey(name = "navigation_assignment_menu_fk"))
    private NavigationMenuEntity menu;

    protected NavigationAssignmentEntity() {}
    public NavigationAssignmentEntity(String userKey, NavigationMenuEntity menu) {
        this.userKey = userKey;
        this.menu = menu;
    }
    public String getUserKey() { return userKey; }
    public NavigationMenuEntity getMenu() { return menu; }
    public void assign(NavigationMenuEntity menu) { this.menu = menu; }
}
