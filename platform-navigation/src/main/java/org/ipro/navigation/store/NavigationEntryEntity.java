package org.ipro.navigation.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import org.ipro.crud.BaseEntity;

@Entity
@Table(name = "navigation_entry")
public class NavigationEntryEntity extends BaseEntity {
    @Column(name = "entry_key", nullable = false, unique = true, length = 190, updatable = false)
    private String key;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "section_id", nullable = false, foreignKey = @ForeignKey(name = "navigation_entry_section_fk"))
    private NavigationSectionEntity section;

    @Column(name = "function_key", nullable = false, length = 190)
    private String functionKey;

    @Column(length = 120)
    private String title;

    @Column(nullable = false)
    private int position;

    protected NavigationEntryEntity() {}

    public NavigationEntryEntity(String key, NavigationSectionEntity section, String functionKey,
                                 String title, int position) {
        this.key = key;
        update(section, functionKey, title, position);
    }

    public void update(NavigationSectionEntity section, String functionKey, String title, int position) {
        this.section = section;
        this.functionKey = functionKey;
        this.title = title;
        this.position = position;
    }

    public String getKey() { return key; }
    public NavigationSectionEntity getSection() { return section; }
    public String getFunctionKey() { return functionKey; }
    public String getTitle() { return title; }
    public int getPosition() { return position; }
}
