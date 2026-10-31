package org.ip.service;

import jakarta.persistence.EntityManager;
import org.ip.application.catalog.NamedOperationOwner;
import org.ip.application.user.UserPasswordChangePermission;
import org.ip.application.user.UserRoleAssignmentPermission;
import org.ip.config.DataInitializer;
import org.ip.model.Role;
import org.ip.model.User;
import org.ip.repository.RoleRepository;
import org.ip.service.AccessGrantAdminService.GrantFlags;
import org.ipro.data.EntityDataAccess;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.ipro.rls.c5.C5AccessDeniedException;
import org.ipro.rls.c5.C5DecisionReason;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C5.8d: назначение ролей и смена пароля — отдельные определения владельца, и проверяются они
 * <b>на прямом вызове сервиса</b>, а не только формой (ADR-0017 §12, матрица П13).
 *
 * <p>Проверяется не «пользователь нажал кнопку», а пара: право на операцию открывает ровно свою
 * операцию и не открывает соседнюю, отказ не оставляет частичной записи, а <b>свою</b> учётную
 * запись не меняет даже держатель обоих прав — иначе право на назначение ролей было бы правом
 * выдать себе любую роль. Контроль невакуумности в каждом отрицании: тем же субъектом та же
 * операция выполняется над другой учётной записью.</p>
 */
@SpringBootTest
@Transactional
class UserAdministrationSelfElevationIT {

    private static final String ASSIGN_ID = UserRoleAssignmentPermission.PERMISSION_ID;
    private static final String PASSWORD_ID = UserPasswordChangePermission.PERMISSION_ID;
    private static final String ASSIGN_KEY = C5PermissionKeys.action(ASSIGN_ID);
    private static final String PASSWORD_KEY = C5PermissionKeys.action(PASSWORD_ID);
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String INITIAL_PASSWORD = "initial-secret";

    @MockitoBean DataInitializer initializer;
    @Autowired UserService users;
    @Autowired EntityDataAccess access;
    @Autowired AccessGrantRepository grants;
    @Autowired RoleRepository roleRepository;
    @Autowired PasswordEncoder passwords;
    @Autowired AccessGrantAdminService accessGrantAdminService;
    @Autowired SecurityContextHolderStrategy securityContextHolderStrategy;
    @Autowired UserRoleAssignmentPermission roleAssignmentPermission;
    @Autowired UserPasswordChangePermission passwordChangePermission;
    @Autowired List<NamedOperationOwner> namedOperationOwners;
    @jakarta.persistence.PersistenceContext EntityManager entityManager;

    private String actor;
    private String memberRole;

    @BeforeEach
    void login() {
        actor = "user-admin-" + UUID.randomUUID().toString().substring(0, 8);
        var context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
            actor, "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        securityContextHolderStrategy.setContext(context);
        SecurityContextHolder.setContext(context);
        memberRole = "MEMBER_" + UUID.randomUUID().toString().substring(0, 8);
        if (roleRepository.findByName(ADMIN_ROLE).isEmpty()) {
            createRoleAsSuperuser(ADMIN_ROLE);
        }
        createRoleAsSuperuser(memberRole);
        RlsTestFixture.grantC5EntityRead(grants, actor, User.class);
        RlsTestFixture.grantC5EntityOperations(grants, actor, User.class);
    }

    @AfterEach
    void logout() {
        securityContextHolderStrategy.clearContext();
        SecurityContextHolder.clearContext();
    }

    /** Право на операцию решает: без него чужие роли не меняются, с ним меняются. */
    @Test
    void changingAnotherUsersRolesNeedsTheAssignmentOperation() {
        User target = createUserAsSuperuser("target-roles-" + actor, memberRole);

        User edited = copyWithAddedRoles(reload(target.getId()), ADMIN_ROLE);
        assertThatThrownBy(() -> users.save(edited))
            .isInstanceOf(C5AccessDeniedException.class)
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().reason())
                .isEqualTo(C5DecisionReason.ACTION_PERMISSION_DENIED));
        assertThat(persistedRoleNames(target.getId()))
            .as("отказ не оставляет частичной записи")
            .containsExactly(memberRole);

        grantAction(ASSIGN_ID);
        User allowed = reload(target.getId());
        allowed.addRole(role(ADMIN_ROLE));
        users.save(allowed);

        assertThat(persistedRoleNames(target.getId()))
            .containsExactlyInAnyOrder(memberRole, ADMIN_ROLE);
    }

    /** Смена пароля — отдельная операция: право на роли её не открывает. */
    @Test
    void changingAPasswordNeedsItsOwnOperation() {
        User target = createUserAsSuperuser("target-password-" + actor, memberRole);
        grantAction(ASSIGN_ID);

        User edited = reload(target.getId());
        edited.setRawPassword("new-secret-value");
        assertThatThrownBy(() -> users.save(edited))
            .isInstanceOf(C5AccessDeniedException.class)
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().reason())
                .isEqualTo(C5DecisionReason.ACTION_PERMISSION_DENIED));
        assertThat(storedPasswordMatches(target.getId(), INITIAL_PASSWORD))
            .as("отказ не меняет пароль")
            .isTrue();
        assertThat(storedPasswordMatches(target.getId(), "new-secret-value")).isFalse();

        grantAction(PASSWORD_ID);
        User allowed = reload(target.getId());
        allowed.setRawPassword("new-secret-value");
        users.save(allowed);

        assertThat(storedPasswordMatches(target.getId(), "new-secret-value")).isTrue();
    }

    /**
     * П13: свою учётную запись не расширяет даже держатель обоих прав. Отказ обязан быть
     * <b>не</b> C5-отказом по гранту: право есть, дело в цели. Контроль — та же роль, выданная
     * другой учётной записи тем же субъектом, выполняется.
     */
    @Test
    void nobodyGivesThemselvesTheAdminRoleEvenHoldingBothOperations() {
        User own = createUserAsSuperuser(actor, memberRole);
        User target = createUserAsSuperuser("target-self-" + actor, memberRole);
        grantAction(ASSIGN_ID);
        grantAction(PASSWORD_ID);

        User edited = copyWithAddedRoles(reload(own.getId()), ADMIN_ROLE);
        assertThatThrownBy(() -> users.save(edited))
            .as("самоповышение не является отказом гранта: право на операцию выдано")
            .isInstanceOf(AccessDeniedException.class)
            .isNotInstanceOf(C5AccessDeniedException.class)
            .hasMessageContaining("своей учётной записи");
        assertThat(persistedRoleNames(own.getId()))
            .as("отказ не оставляет частичной записи")
            .containsExactly(memberRole);

        User other = reload(target.getId());
        other.addRole(role(ADMIN_ROLE));
        users.save(other);
        assertThat(persistedRoleNames(target.getId()))
            .as("право на операцию настоящее: чужая учётная запись получает ту же роль")
            .containsExactlyInAnyOrder(memberRole, ADMIN_ROLE);

        assertThat(roleAssignmentPermission.canAssignRoles())
            .as("право есть — форму спрашивают именно об этом")
            .isTrue();
        assertThat(roleAssignmentPermission.canAssignRolesFor(actor)).isFalse();
        assertThat(roleAssignmentPermission.canAssignRolesFor(other.getUsername())).isTrue();
    }

    /** Набор ролей не менялся — это обновление, а не назначение: своего права не требуется. */
    @Test
    void anUnchangedOwnRoleSetIsAnUpdateNotAnAssignment() {
        User own = createUserAsSuperuser(actor, memberRole);
        grantAction(PASSWORD_ID);

        User edited = reload(own.getId());
        edited.setRawPassword("own-new-secret");
        users.save(edited);

        assertThat(persistedRoleNames(own.getId())).containsExactly(memberRole);
        assertThat(storedPasswordMatches(own.getId(), "own-new-secret")).isTrue();
    }

    /** Создание учётной записи несёт и роли, и пароль: спрашиваются обе операции. */
    @Test
    void creatingAUserAsksForBothOperations() {
        User created = new User("created-" + actor, null);
        created.setRawPassword(INITIAL_PASSWORD);
        created.addRole(role(memberRole));

        assertThatThrownBy(() -> users.save(created))
            .isInstanceOf(C5AccessDeniedException.class)
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().key())
                .isEqualTo(PASSWORD_KEY));
        assertThat(created.getPassword())
            .as("отказ не оставляет изменённую сущность")
            .isNull();

        grantAction(PASSWORD_ID);
        assertThatThrownBy(() -> users.save(created))
            .as("второе право не выдаётся первым")
            .isInstanceOf(C5AccessDeniedException.class)
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().key())
                .isEqualTo(ASSIGN_KEY));

        grantAction(ASSIGN_ID);
        User saved = users.save(created);

        assertThat(saved.getId()).isNotNull();
        assertThat(persistedRoleNames(saved.getId())).containsExactly(memberRole);
        assertThat(storedPasswordMatches(saved.getId(), INITIAL_PASSWORD)).isTrue();
    }

    /** Оба определения зарегистрированы и выдаются одним флагом «выполнение», без чтения. */
    @Test
    void theTwoOperationsAreRegisteredAndGrantable() {
        assertThat(accessGrantAdminService.availableDimensions()).contains(ASSIGN_KEY, PASSWORD_KEY);
        assertThat(accessGrantAdminService.isNamedOperationDimension(ASSIGN_KEY)).isTrue();
        assertThat(accessGrantAdminService.isNamedOperationDimension(PASSWORD_KEY)).isTrue();
        assertThat(accessGrantAdminService.expressibleFlags(ASSIGN_KEY))
            .isEqualTo(new GrantFlags(false, true, false));
        assertThat(accessGrantAdminService.expressibleFlags(PASSWORD_KEY))
            .isEqualTo(new GrantFlags(false, true, false));

        assertThat(namedOperationOwners)
            .as("экран выдачи показывает название операции, а не сырой ключ")
            .anySatisfy(owner -> {
                assertThat(owner.permissionId()).isEqualTo(ASSIGN_ID);
                assertThat(owner.title()).isEqualTo(UserRoleAssignmentPermission.TITLE);
            })
            .anySatisfy(owner -> {
                assertThat(owner.permissionId()).isEqualTo(PASSWORD_ID);
                assertThat(owner.title()).isEqualTo(UserPasswordChangePermission.TITLE);
            });
    }

    // === Фикстура ===

    /**
     * Копия строки с добавленными ролями: отказ проверяется на экземпляре, которого нет в живой
     * сессии, иначе снятое с ниоткуда изменение осталaлось бы в сессии и его сбросил бы первый же
     * запрос решения — это предмет границы записи (П09), а не этого правила.
     */
    private User copyWithAddedRoles(User target, String... roleNames) {
        User copy = new User(target.getUsername(), target.getPassword());
        copy.setId(target.getId());
        copy.setVersion(target.getVersion());
        copy.setEnabled(target.isEnabled());
        for (Role role : target.getRoles()) {
            copy.addRole(role);
        }
        for (String roleName : roleNames) {
            copy.addRole(role(roleName));
        }
        return copy;
    }


    /** Выдача ровно одной операции — админ-сервисом, как это делает экран доступа. */
    private void grantAction(String permissionId) {
        RlsTestFixture.grantC5Action(grants, actor, permissionId);
    }

    private void createRoleAsSuperuser(String name) {
        RlsTestFixture.callAsSuperuser(grants, () -> access.create(Role.class, new Role(name)));
    }

    private User createUserAsSuperuser(String username, String... roleNames) {
        return RlsTestFixture.callAsSuperuserWithRead(grants, new Class<?>[]{User.class}, () -> {
            User user = new User(username, passwords.encode(INITIAL_PASSWORD));
            for (String roleName : roleNames) {
                user.addRole(role(roleName));
            }
            return access.create(User.class, user);
        });
    }

    /** Роль читается заново: после очистки сессии ссылка на неё обязана быть управляемой снова. */
    private Role role(String name) {
        return roleRepository.findByName(name).orElseThrow();
    }

    /** Строка, прочитанная заново тем же canonical read, которым пользуется сервис. */
    private User reload(Long id) {
        entityManager.flush();
        entityManager.clear();
        return access.detail(User.class, id).orElseThrow();
    }

    /** Набор ролей читается после flush/clear: иначе утверждение проверяло бы кэш сессии. */
    private Set<String> persistedRoleNames(Long id) {
        return reload(id).getRoles().stream().map(Role::getName).collect(Collectors.toSet());
    }

    private boolean storedPasswordMatches(Long id, String rawPassword) {
        return passwords.matches(rawPassword, reload(id).getPassword());
    }
}
