package org.ipro.navigation.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import org.ipro.crud.BaseEntity;

@Entity
@Table(name = "navigation_section", uniqueConstraints = {
    @UniqueConstraint(name = "navigation_section_key_uk", columnNames = {"menu_id", "section_key"}),
    @UniqueConstraint(name = "navigation_section_area_uk", columnNames = {"menu_id", "area_key"})
})
public class NavigationSectionEntity extends BaseEntity {
    @Column(name = "section_key", nullable = false, length = 190, updatable = false)
    private String key;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_id", nullable = false, updatable = false, foreignKey = @ForeignKey(name = "navigation_section_menu_fk"))
    private NavigationMenuEntity menu;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", foreignKey = @ForeignKey(name = "navigation_section_parent_fk"))
    private NavigationSectionEntity parent;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 64)
    private String icon;

    @Column(nullable = false)
    private int position;

    @Column(name = "area_key", length = 190)
    private String areaKey;

    protected NavigationSectionEntity() {}

    public NavigationSectionEntity(NavigationMenuEntity menu, String key, NavigationSectionEntity parent, String title,
                                   String icon, int position, String areaKey) {
        this.menu = menu;
        this.key = key;
        update(parent, title, icon, position, areaKey);
    }

    public void update(NavigationSectionEntity parent, String title, String icon, int position, String areaKey) {
        this.parent = parent;
        this.title = title;
        this.icon = icon;
        this.position = position;
        this.areaKey = areaKey;
    }

    public void detachParent() { parent = null; }

    public void clearArea() { areaKey = null; }

    public String getKey() { return key; }
    public NavigationSectionEntity getParent() { return parent; }
    public String getTitle() { return title; }
    public String getIcon() { return icon; }
    public int getPosition() { return position; }
    public String getAreaKey() { return areaKey; }
}
