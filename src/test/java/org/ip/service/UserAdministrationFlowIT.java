package org.ip.service;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import org.ip.application.user.UserPasswordChangePermission;
import org.ip.application.user.UserRoleAssignmentPermission;
import org.ip.config.DataInitializer;
import org.ip.model.Role;
import org.ip.model.User;
import org.ip.views.forms.UserItemForm;
import org.ipro.form.registry.FormResolver;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.c5.C5AccessDeniedException;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real User form → password normalization → canonical persistence, with explicit C5 grants. */
@SpringBootTest
@Transactional
class UserAdministrationFlowIT {
    @MockitoBean DataInitializer initializer;
    @Autowired FormResolver forms;
    @Autowired UserService users;
    @Autowired org.ipro.data.EntityDataAccess access;
    @Autowired PasswordEncoder passwords;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    private String actor;

    @BeforeEach
    void login() {
        actor = "user-ui-" + UUID.randomUUID().toString().substring(0, 8);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            actor, "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        UI.setCurrent(new UI());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void logout() {
        UI.setCurrent(null);
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void adminAuthorityDoesNotReplaceExplicitUserReadGrant() {
        assertThatThrownBy(users::findAll).isInstanceOf(C5AccessDeniedException.class)
            .hasMessageContaining(User.class.getName());
    }

    @Test
    void creationNeedsRoleReadEvenWithUserRead() {
        grant(User.class);
        assertThatThrownBy(() -> forms.resolveItemForm(User.class, null, null, Map.of()))
            .isInstanceOf(C5AccessDeniedException.class).hasMessageContaining(Role.class.getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsUserThroughExistingFormAndReturnsItInCanonicalList() {
        grant(User.class);
        grant(Role.class);
        // ADR-0017: ROLE_ADMIN и read-грант операцией не распоряжаются — создание пользователя
        // идёт через canonical write и требует отдельного права на операцию.
        grantOperations(User.class);
        // Роль пишется тем же canonical path: прямое persist мимо preflight отказало бы.
        grantOperations(Role.class);
        // C5.8d: создание учётной записи назначает роли и пароль — это отдельные операции
        // владельца, и generic-записи User их не заменяет (ADR-0017 §12).
        grantAction(UserRoleAssignmentPermission.PERMISSION_ID);
        grantAction(UserPasswordChangePermission.PERMISSION_ID);
        Role role = access.create(Role.class, new Role("REST_READER_" + actor));

        var form = forms.resolveItemForm(User.class, null, null, Map.of());
        assertThat(form).isInstanceOf(UserItemForm.class);
        form.initializeNewEntity(null);
        ((TextField) form.getField("username")).setValue("rest-check-" + actor);
        PasswordField password = form.getFormLayout().getChildren().filter(PasswordField.class::isInstance)
            .map(PasswordField.class::cast).findFirst().orElseThrow();
        password.setValue("test-only-secret");
        MultiSelectComboBox<Role> roles = form.getFormLayout().getChildren()
            .filter(MultiSelectComboBox.class::isInstance)
            .map(component -> (MultiSelectComboBox<Role>) component).findFirst().orElseThrow();
        roles.setValue(Set.of(role));

        User saved = users.save(form.getEntity());
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getRawPassword()).isNull();
        assertThat(passwords.matches("test-only-secret", saved.getPassword())).isTrue();
        assertThat(saved.getRoles()).extracting(Role::getName).contains(role.getName());
        assertThat(users.findAll()).extracting(User::getUsername).contains(saved.getUsername());
    }

    private void grant(Class<?> type) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(actor);
        grant.setDimension(C5PermissionKeys.entityRead(type));
        grant.setCanRead(true);
        entityManager.persist(grant);
        entityManager.flush();
    }

    /** Именованная операция: право выражается строкой ключа {@code ACTION:...} (ADR-0019 §1). */
    private void grantAction(String permissionId) {
        AccessGrant action = new AccessGrant();
        action.setSubjectType(AccessGrant.SubjectType.USER);
        action.setSubjectKey(actor);
        action.setDimension(C5PermissionKeys.action(permissionId));
        action.setCanUpdate(true);
        entityManager.persist(action);
        entityManager.flush();
    }

    /** Операция — отдельная строка того же ключа {@code ENTITY:<SimpleName>} (ADR-0017 §3). */
    private void grantOperations(Class<?>... types) {
        for (Class<?> type : types) {
            AccessGrant operations = new AccessGrant();
            operations.setSubjectType(AccessGrant.SubjectType.USER);
            operations.setSubjectKey(actor);
            operations.setDimension(C5PermissionKeys.entityOperations(type));
            operations.setCanUpdate(true);
            operations.setCanDelete(true);
            entityManager.persist(operations);
        }
        entityManager.flush();
    }
}
