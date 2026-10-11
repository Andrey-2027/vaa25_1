package org.ip.views.forms;

import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.textfield.PasswordField;
import org.ip.application.user.UserPasswordChangePermission;
import org.ip.application.user.UserRoleAssignmentPermission;
import org.ipro.form.FieldFactory;
import org.ipro.form.builtin.ItemForm;
import org.ipro.metadata.EntityMetadataInfo;
import org.ip.model.Role;
import org.ip.model.User;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Форма элемента для {@link User} — написана вручную (наследование ItemForm, composition
 * не в буквальном смысле "нет наследования вообще", а в смысле "дополняем существующий
 * компонент прямым кодом", как WorkshopItemForm) — два аспекта, которые generic-механизм
 * не покрывает:
 *   - пароль нужно хэшировать при сохранении (см. User.rawPassword/UserService) — не
 *     обычное свойство сущности, которое можно просто отрисовать и записать как есть;
 *   - множественный выбор ролей (Set&lt;Role&gt;) — сейчас нет FieldType для
 *     "множественная ссылка на сущности", это единичный случай, не стоит обобщать
 *     платформу под него заранее.
 *
 * <p><b>Доступность полей — тот же ответ, что у серверного пути.</b> И пароль, и набор ролей
 * защищены отдельными операциями владельца, поэтому поле спрашивает решение у
 * {@link UserRoleAssignmentPermission} и {@link UserPasswordChangePermission}, а не живёт по
 * собственному условию формы: открытое поле, которое сервер откажет, — обещание, которого никто
 * не исполнит (то же правило, что у кнопок AttributeTypeForm). Значение read-only поля остаётся
 * в форме, поэтому сохранение без изменения ролей приходит на границу как «набор не менялся».</p>
 */
public class UserItemForm extends ItemForm<User> {

    private static final String PASSWORD_HINT =
        "Оставьте пустым при редактировании, чтобы не менять пароль";
    private static final String NO_PASSWORD_RIGHT =
        "Нет права «Смена пароля пользователя» — пароль меняет держатель права";
    private static final String NO_ROLES_RIGHT =
        "Нет права «Назначение ролей пользователя» — роли назначает держатель права";
    private static final String OWN_ROLES_HINT =
        "Роли своей учётной записи назначает другой держатель права";

    private final PasswordField passwordField = new PasswordField("Пароль");
    private final MultiSelectComboBox<Role> rolesField = new MultiSelectComboBox<>("Роли");
    private final UserRoleAssignmentPermission rolePermissions;
    private final UserPasswordChangePermission passwordPermissions;

    public UserItemForm(EntityMetadataInfo metadata, FieldFactory fieldFactory, List<Role> allRoles,
                        UserRoleAssignmentPermission rolePermissions,
                        UserPasswordChangePermission passwordPermissions) {
        super(metadata, fieldFactory);

        this.rolePermissions = rolePermissions;
        this.passwordPermissions = passwordPermissions;

        passwordField.setWidthFull();
        passwordField.setHelperText(PASSWORD_HINT);

        rolesField.setWidthFull();
        rolesField.setItems(allRoles);
        rolesField.setItemLabelGenerator(Role::getName);

        getFormLayout().add(passwordField, rolesField);
        applyAdministrationFieldAccess(null);
    }

    @Override
    public void setEntity(User entity) {
        super.setEntity(entity);
        passwordField.clear();
        passwordField.setRequiredIndicatorVisible(entity == null || entity.getId() == null);
        rolesField.setValue(entity != null ? new HashSet<>(entity.getRoles()) : Set.of());
        applyAdministrationFieldAccess(entity != null ? entity.getUsername() : null);
    }

    /**
     * Создание идёт мимо {@link #setEntity}: форма готовит новую сущность сама, поэтому доступ
     * полей пересчитывается и здесь — иначе на новой записи осталось бы решение от прошлой.
     */
    @Override
    public User initializeNewEntity(Map<String, Object> initialValues) {
        User created = super.initializeNewEntity(initialValues);
        passwordField.clear();
        passwordField.setRequiredIndicatorVisible(true);
        rolesField.setValue(Set.of());
        applyAdministrationFieldAccess(null);
        return created;
    }

    @Override
    public User getEntity() {
        User user = super.getEntity();
        user.setRoles(new HashSet<>(rolesField.getValue()));
        String password = passwordField.getValue();
        user.setRawPassword(password == null || password.isBlank() ? null : password);
        return user;
    }

    /** Решение о доступности полей — у владельцев операций, а не у формы. */
    private void applyAdministrationFieldAccess(String targetUsername) {
        boolean rolesAllowed = rolePermissions.canAssignRoles();
        boolean rolesForThisRecord = rolePermissions.canAssignRolesFor(targetUsername);
        rolesField.setReadOnly(!rolesForThisRecord);
        rolesField.setHelperText(rolesForThisRecord ? null
            : rolesAllowed ? OWN_ROLES_HINT : NO_ROLES_RIGHT);

        boolean passwordAllowed = passwordPermissions.canChangePassword();
        passwordField.setReadOnly(!passwordAllowed);
        passwordField.setHelperText(passwordAllowed ? PASSWORD_HINT : NO_PASSWORD_RIGHT);
    }
}
