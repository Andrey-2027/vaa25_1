package org.ip.service;

import org.ip.application.user.PersistedUserState;
import org.ip.application.user.UserPasswordChangePermission;
import org.ip.application.user.UserRoleAssignmentPermission;
import org.ip.model.Role;
import org.ip.model.User;
import org.ipro.crud.ValidationException;
import org.ipro.data.CanonicalEntityService;
import org.ipro.rls.RlsCurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Два предметных правила {@code UserService}: нормализация пароля и административная авторизация
 * (роли, пароль) — то, что сервис добавляет к canonical handle.
 *
 * <p>Тест проверяет ровно шов: что хэшируется, что пустое поле не перетирает существующий хэш,
 * что новый пользователь без пароля отклоняется, когда спрашивается право на операцию, а когда
 * нет, и что отказ не оставляет изменённую сущность. Настоящее решение C5 и его строка гранта —
 * дело {@code UserAdministrationSelfElevationIT}; здесь владельцы операций — моки, и проверяется
 * <b>момент</b> вопроса, а не ответ.</p>
 */
class UserServiceTest {

    private static final String ACTOR = "admin-actor";

    private CanonicalEntityService<User> canonical;
    private PasswordEncoder encoder;
    private UserRoleAssignmentPermission roleAssignment;
    private UserPasswordChangePermission passwordChange;
    private RlsCurrentUser currentUser;
    private PersistedUserState persistedState;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        canonical = mock(CanonicalEntityService.class);
        encoder = mock(PasswordEncoder.class);
        roleAssignment = mock(UserRoleAssignmentPermission.class);
        passwordChange = mock(UserPasswordChangePermission.class);
        currentUser = mock(RlsCurrentUser.class);
        persistedState = mock(PersistedUserState.class);
        when(currentUser.username()).thenReturn(ACTOR);
        when(canonical.findById(any())).thenReturn(Optional.empty());
        when(encoder.encode(any())).thenAnswer(invocation -> "hash:" + invocation.getArgument(0));
    }

    private UserService service() {
        return new UserService(canonical, encoder, roleAssignment, passwordChange, currentUser,
            persistedState);
    }

    @Test
    void saveHashesRawPasswordAndClearsItBeforeDelegating() {
        User user = new User("ivan", null);
        user.setRawPassword("secret123");

        service().save(user);

        assertThat(user.getPassword()).isEqualTo("hash:secret123");
        assertThat(user.getRawPassword()).isNull();
        verify(passwordChange).requireChangePassword(ACTOR);
        verify(canonical).save(user);
    }

    @Test
    void updateWithoutRawPasswordKeepsExistingHash() {
        User existing = new User("ivan", "old-hash");
        existing.setId(7L);
        persistedState(7L, "ivan");

        service().update(existing);

        assertThat(existing.getPassword()).isEqualTo("old-hash");
        verify(encoder, never()).encode(any());
        verifyNoInteractions(passwordChange, roleAssignment);
        verify(canonical).update(existing);
    }

    /**
     * Живая сессия возвращает тот же управляемый экземпляр, который вызывающий уже изменил:
     * сравнение с его коллекцией было бы сравнением значения с самим собой, поэтому сохранённый
     * набор берётся у отдельного чтения предсостояния.
     */
    @Test
    void thePersistedSetIsReadApartFromTheSessionInstance() {
        User incoming = new User("ivan", "hash");
        incoming.setId(7L);
        incoming.setRoles(Set.of(new Role("ADMIN"), new Role("MANAGER")));
        when(canonical.findById(7L)).thenReturn(Optional.of(incoming));
        when(persistedState.of(7L)).thenReturn(Optional.of(
            new PersistedUserState.State("ivan", Set.of("ADMIN"))));

        service().update(incoming);

        verify(roleAssignment)
            .requireAssignRoles(ACTOR); // набор отличается от сохранённого
    }

    @Test
    void newUserWithoutPasswordIsRejectedBeforeTheBoundary() {
        User user = new User("ivan", null);

        assertThatThrownBy(() -> service().save(user))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Укажите пароль");
        verifyNoInteractions(passwordChange, roleAssignment);
        verify(canonical, never()).save(any());
    }

    @Test
    void createHashesRawPassword() {
        User user = new User("petr", null);
        user.setRawPassword("  newpass  ");

        service().create(user);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(canonical).create(captor.capture());
        assertThat(captor.getValue().getRawPassword()).isNull();
        assertThat(captor.getValue().getPassword()).isEqualTo("hash:  newpass  ");
    }

    @Test
    void readsGoToTheSameBoundaryUntouched() {
        service().findById(1L);
        service().findAll();
        service().search("iva");
        service().delete(2L);

        verify(canonical).findById(1L);
        verify(canonical).findAll();
        verify(canonical).search("iva");
        verify(canonical).delete(2L);
    }

    @Test
    void assigningRolesToANewUserAsksForTheAssignmentOperation() {
        User user = new User("petr", null);
        user.setRawPassword("secret123");
        user.setRoles(Set.of(new Role("MANAGER")));

        service().create(user);

        verify(roleAssignment).requireAssignRoles(ACTOR);
    }

    @Test
    void aNewUserWithoutRolesDoesNotAskForTheAssignmentOperation() {
        User user = new User("petr", null);
        user.setRawPassword("secret123");

        service().create(user);

        verify(roleAssignment, never()).requireAssignRoles(any());
    }

    @Test
    void onlyAChangedRoleSetIsAnAssignment() {
        persistedState(7L, "ivan", "ADMIN");
        User incoming = new User("ivan", "hash");
        incoming.setId(7L);
        incoming.setRoles(Set.of(new Role("ADMIN"), new Role("MANAGER")));

        service().update(incoming);
        verify(roleAssignment).requireAssignRoles(ACTOR);

        User unchanged = new User("ivan", "hash");
        unchanged.setId(7L);
        unchanged.setRoles(Set.of(new Role("ADMIN")));
        service().update(unchanged);

        verify(roleAssignment).requireAssignRoles(ACTOR); // один вызов: второй набор не менялся
    }

    @Test
    void anEmptyRoleSetIsReadAsRemovalAndAsksForTheAssignmentOperation() {
        persistedState(7L, "ivan", "ADMIN");
        User incoming = new User("ivan", "hash");
        incoming.setId(7L);

        service().update(incoming);

        verify(roleAssignment).requireAssignRoles(ACTOR);
    }

    /**
     * Отказ не от гранта: право есть (мок отвечает безотказно), но цель — своя учётная запись.
     * Изменённая сущность при этом не уходит на границу.
     */
    @Test
    void nobodyChangesTheirOwnRoleSet() {
        persistedState(7L, ACTOR, "MANAGER");
        User incoming = new User(ACTOR, "hash");
        incoming.setId(7L);
        incoming.setRoles(Set.of(new Role("ADMIN")));

        assertThatThrownBy(() -> service().update(incoming))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("своей учётной записи");
        verify(roleAssignment).requireAssignRoles(ACTOR);
        verify(canonical, never()).update(any());
    }

    /**
     * Цель определяется сохранённой строкой, а не присланным именем: иначе подмена имени в запросе
     * выдала бы свою строку за чужую и сняла бы запрет.
     */
    @Test
    void theTargetIsThePersistedRecordNotTheSubmittedName() {
        persistedState(7L, ACTOR, "MANAGER");
        User incoming = new User("somebody-else", "hash");
        incoming.setId(7L);
        incoming.setRoles(Set.of(new Role("ADMIN")));

        assertThatThrownBy(() -> service().update(incoming))
            .isInstanceOf(AccessDeniedException.class);
        verify(canonical, never()).update(any());
    }

    /** Строку не видно — решение о ней принимает граница записи, сервис не подменяет его своим. */
    @Test
    void anInvisibleRecordLeavesTheDecisionToTheWriteBoundary() {
        User incoming = new User("ivan", "hash");
        incoming.setId(99L);
        incoming.setRoles(Set.of(new Role("ADMIN")));

        service().update(incoming);

        verify(canonical).update(incoming);
    }

    /**
     * Сохранённое состояние пользователя: право чтения и видимость строки даёт canonical read,
     * значения — отдельное чтение предсостояния.
     */
    private void persistedState(Long id, String username, String... roleNames) {
        User stored = new User(username, "stored-hash");
        stored.setId(id);
        when(canonical.findById(id)).thenReturn(Optional.of(stored));
        when(persistedState.of(id)).thenReturn(Optional.of(
            new PersistedUserState.State(username, Set.of(roleNames))));
    }
}
