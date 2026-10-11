package org.ip.views.forms;

import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.textfield.PasswordField;
import org.ip.application.user.UserPasswordChangePermission;
import org.ip.application.user.UserRoleAssignmentPermission;
import org.ip.model.Role;
import org.ip.model.User;
import org.ipro.crud.LookupService;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.EntityMetadataInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Полем распоряжается то же решение, что спрашивает серверный путь (C5.8d, ADR-0017 §12): роли и
 * пароль защищены отдельными операциями владельца, поэтому открытое поле было бы обещанием,
 * которого сервер не исполнит, а закрытое без причины — отказом, которого сервер не сделает.
 *
 * <p>Проверяются три состояния: чужая учётная запись при выданных правах, своя учётная запись (её
 * роли не назначает никто, но пароль своей записи меняет держатель права) и субъект без прав.</p>
 */
class UserItemFormAccessTest {

    private final UserRoleAssignmentPermission rolePermissions =
        mock(UserRoleAssignmentPermission.class);
    private final UserPasswordChangePermission passwordPermissions =
        mock(UserPasswordChangePermission.class);

    @Test
    void aForeignRecordOpensBothFieldsWhenTheOperationsAreGranted() {
        when(rolePermissions.canAssignRoles()).thenReturn(true);
        when(rolePermissions.canAssignRolesFor("other")).thenReturn(true);
        when(passwordPermissions.canChangePassword()).thenReturn(true);

        UserItemForm form = form();
        form.setEntity(user("other"));

        assertThat(rolesField(form).isReadOnly()).isFalse();
        assertThat(rolesField(form).getHelperText()).isNull();
        assertThat(passwordField(form).isReadOnly()).isFalse();
        assertThat(passwordField(form).getHelperText()).contains("Оставьте пустым");
    }

    @Test
    void anOwnRecordKeepsItsRolesClosedButItsPasswordOpen() {
        when(rolePermissions.canAssignRoles()).thenReturn(true);
        when(rolePermissions.canAssignRolesFor("self")).thenReturn(false);
        when(passwordPermissions.canChangePassword()).thenReturn(true);

        UserItemForm form = form();
        form.setEntity(user("self"));

        assertThat(rolesField(form).isReadOnly()).isTrue();
        assertThat(rolesField(form).getHelperText())
            .contains("своей учётной записи");
        assertThat(passwordField(form).isReadOnly())
            .as("смена своего пароля — отдельное право, а не самоповышение")
            .isFalse();
    }

    @Test
    void withoutTheOperationsBothFieldsAreClosedWithTheirReason() {
        when(rolePermissions.canAssignRoles()).thenReturn(false);
        when(passwordPermissions.canChangePassword()).thenReturn(false);

        UserItemForm form = form();
        form.setEntity(user("other"));

        assertThat(rolesField(form).isReadOnly()).isTrue();
        assertThat(rolesField(form).getHelperText())
            .isEqualTo("Нет права «Назначение ролей пользователя» — роли назначает держатель права");
        assertThat(passwordField(form).isReadOnly()).isTrue();
        assertThat(passwordField(form).getHelperText())
            .isEqualTo("Нет права «Смена пароля пользователя» — пароль меняет держатель права");
    }

    /** Создание идёт мимо {@code setEntity}: решение пересчитывается и там. */
    @Test
    void creatingARecordRecomputesTheSameDecision() {
        when(rolePermissions.canAssignRoles()).thenReturn(true);
        when(rolePermissions.canAssignRolesFor(null)).thenReturn(true);
        when(passwordPermissions.canChangePassword()).thenReturn(true);

        UserItemForm form = form();
        form.setEntity(user("self"));
        assertThat(rolesField(form).isReadOnly()).isTrue();

        form.initializeNewEntity(null);

        assertThat(rolesField(form).isReadOnly())
            .as("новая запись не является своей: учётной записи ещё нет")
            .isFalse();
        assertThat(passwordField(form).isReadOnly()).isFalse();
    }

    private UserItemForm form() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(User.class).when(metadata).getEntityClass();
        when(metadata.getFormFields()).thenReturn(List.of());
        FieldFactory fieldFactory =
            new FieldFactory(mock(LookupService.class), mock(SelectionFormAssembler.class));
        return new UserItemForm(metadata, fieldFactory, List.of(new Role("ADMIN")),
            rolePermissions, passwordPermissions);
    }

    private static User user(String username) {
        User user = new User(username, "stored-hash");
        user.setId(7L);
        return user;
    }

    private static MultiSelectComboBox<?> rolesField(UserItemForm form) {
        return form.getFormLayout().getChildren()
            .filter(MultiSelectComboBox.class::isInstance)
            .map(MultiSelectComboBox.class::cast).findFirst().orElseThrow();
    }

    private static PasswordField passwordField(UserItemForm form) {
        return form.getFormLayout().getChildren()
            .filter(PasswordField.class::isInstance)
            .map(PasswordField.class::cast).findFirst().orElseThrow();
    }
}
