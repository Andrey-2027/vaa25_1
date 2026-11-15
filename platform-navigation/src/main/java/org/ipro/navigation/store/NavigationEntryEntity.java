package org.ipro.navigation.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import org.ipro.navigation.NavigationImportance;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import org.ipro.crud.BaseEntity;

@Entity
@Table(name = "navigation_entry", uniqueConstraints = @UniqueConstraint(
    name = "navigation_entry_key_uk", columnNames = {"menu_id", "entry_key"}))
public class NavigationEntryEntity extends BaseEntity {
    @Column(name = "entry_key", nullable = false, length = 190, updatable = false)
    private String key;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_id", nullable = false, updatable = false, foreignKey = @ForeignKey(name = "navigation_entry_menu_fk"))
    private NavigationMenuEntity menu;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "section_id", nullable = false, foreignKey = @ForeignKey(name = "navigation_entry_section_fk"))
    private NavigationSectionEntity section;

    @Column(name = "function_key", nullable = false, length = 190)
    private String functionKey;

    @Column(length = 120)
    private String title;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.ColumnDefault("'NORMAL'")
    @Column(nullable = false, length = 12)
    private NavigationImportance importance;

    protected NavigationEntryEntity() {}

    public NavigationEntryEntity(NavigationMenuEntity menu, String key, NavigationSectionEntity section, String functionKey,
                                 String title, int position, NavigationImportance importance) {
        this.menu = menu;
        this.key = key;
        update(section, functionKey, title, position, importance);
    }

    public void update(NavigationSectionEntity section, String functionKey, String title, int position, NavigationImportance importance) {
        this.section = section;
        this.functionKey = functionKey;
        this.title = title;
        this.position = position;
        this.importance = importance;
    }

    public NavigationImportance getImportance() { return importance; }
    public String getKey() { return key; }
    public NavigationSectionEntity getSection() { return section; }
    public String getFunctionKey() { return functionKey; }
    public String getTitle() { return title; }
    public int getPosition() { return position; }
}
