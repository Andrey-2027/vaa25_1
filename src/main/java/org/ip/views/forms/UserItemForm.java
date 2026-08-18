package org.ip.views.forms;

import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.textfield.PasswordField;
import org.ip.form.FieldFactory;
import org.ip.form.builtin.ItemForm;
import org.ipro.metadata.EntityMetadataInfo;
import org.ip.model.Role;
import org.ip.model.User;

import java.util.HashSet;
import java.util.List;
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
 */
public class UserItemForm extends ItemForm<User> {

    private final PasswordField passwordField = new PasswordField("Пароль");
    private final MultiSelectComboBox<Role> rolesField = new MultiSelectComboBox<>("Роли");

    public UserItemForm(EntityMetadataInfo metadata, FieldFactory fieldFactory, List<Role> allRoles) {
        super(metadata, fieldFactory);

        passwordField.setWidthFull();
        passwordField.setHelperText("Оставьте пустым при редактировании, чтобы не менять пароль");

        rolesField.setWidthFull();
        rolesField.setItems(allRoles);
        rolesField.setItemLabelGenerator(Role::getName);

        getFormLayout().add(passwordField, rolesField);
    }

    @Override
    public void setEntity(User entity) {
        super.setEntity(entity);
        passwordField.clear();
        passwordField.setRequiredIndicatorVisible(entity == null || entity.getId() == null);
        rolesField.setValue(entity != null ? new HashSet<>(entity.getRoles()) : Set.of());
    }

    @Override
    public User getEntity() {
        User user = super.getEntity();
        user.setRoles(new HashSet<>(rolesField.getValue()));
        String password = passwordField.getValue();
        user.setRawPassword(password == null || password.isBlank() ? null : password);
        return user;
    }
}
