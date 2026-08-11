package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import org.ip.metadata.annotation.EntityMetadata;
import org.ip.metadata.annotation.FieldMetadata;
import org.ip.metadata.annotation.GridColumn;
import org.ipro.crud.BaseEntity;

import java.util.HashSet;
import java.util.Set;

/**
 * Роль — теперь заводится администратором через обычный ListForm/ItemForm, а не жёстко
 * зашита в DataInitializer. Используется и Spring Security (авторизация), и AccessGrant
 * (RLS — subjectKey = Role.getName() для строк subjectType = ROLE).
 */
@Entity
@Table(name = "role")
@EntityMetadata(
    listFormTitle = "Роли",
    itemFormTitle = "Роль",
    order = 920,
    icon = "USERS"
)
public class Role extends BaseEntity {

    @NotBlank
    @Column(nullable = false, unique = true)
    @FieldMetadata(label = "Название", required = true, order = 1,
        grid = @GridColumn(order = 1, flexGrow = 1))
    private String name;

    @ManyToMany(mappedBy = "roles")
    private Set<User> users = new HashSet<>();

    public Role() {
    }

    public Role(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Set<User> getUsers() {
        return users;
    }

    public void setUsers(Set<User> users) {
        this.users = users;
    }

    @Override
    public String toString() {
        return name;
    }
}
