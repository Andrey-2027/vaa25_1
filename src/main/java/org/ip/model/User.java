package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.crud.BaseEntity;

import java.util.HashSet;
import java.util.Set;

/**
 * Пользователь. Форма элемента — не generic (см. UserItemForm/UserFormConfig): пароль
 * нужно хэшировать при сохранении, а множественный выбор ролей (Set&lt;Role&gt;) сейчас
 * не выражается через @FieldMetadata (нет ENTITY_REFERENCE_MULTI — см. обсуждение).
 * Поэтому у password/roles намеренно нет @FieldMetadata — generic-механизм их не видит
 * ни в гриде, ни в форме; они редактируются только в UserItemForm явным кодом.
 */
@Entity
@Table(name = "app_user")
@EntityMetadata(
    listFormTitle = "Пользователи",
    itemFormTitle = "Пользователь",
    order = 930,
    icon = "USER"
)
public class User extends BaseEntity {

    @NotBlank
    @Size(min = 3, max = 50)
    @Column(nullable = false, unique = true)
    @FieldMetadata(label = "Логин", required = true, order = 1,
        grid = @GridColumn(order = 1, width = "200px"))
    private String username;

    @NotBlank
    @Size(min = 6, max = 100)
    @Column(nullable = false)
    private String password;

    /**
     * Новый пароль в открытом виде — только для передачи из UserItemForm в UserService.save()
     * (который хэширует и кладёт в {@link #password}). Никогда не сохраняется в БД
     * (@Transient) и никогда не читается напрямую как источник правды о текущем пароле.
     */
    @Transient
    private String rawPassword;

    @Column(nullable = false)
    @FieldMetadata(label = "Включён", order = 2, grid = @GridColumn(order = 2, width = "100px"))
    private boolean enabled = true;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_role",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Fetch(FetchMode.SUBSELECT)
    private Set<Role> roles = new HashSet<>();

    public User() {
    }

    public User(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRawPassword() {
        return rawPassword;
    }

    public void setRawPassword(String rawPassword) {
        this.rawPassword = rawPassword;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }

    public void addRole(Role role) {
        this.roles.add(role);
    }

    public void removeRole(Role role) {
        this.roles.remove(role);
    }

    @Override
    public String toString() {
        return username;
    }
}
