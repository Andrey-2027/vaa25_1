package org.ip.config;

import jakarta.persistence.EntityManager;
import org.ip.application.catalog.AttributeValuePermissions;
import org.ip.application.user.UserPasswordChangePermission;
import org.ip.application.user.UserRoleAssignmentPermission;
import org.ip.model.Nomenclature;
import org.ip.model.Role;
import org.ip.model.User;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.ip.service.AccessGrantAdminService;
import org.ip.service.AccessGrantAdminService.GrantFlags;
import org.ip.service.UserService;
import org.ipro.data.EntityDataAccess;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.ipro.rls.c5.C5AccessDeniedException;
import org.ipro.rls.c5.C5DecisionReason;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.rls.c5.C5ReadPermissionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C5.8: первичная выдача — идемпотентная материализация объявленных определений в строки грантов,
 * и отчёт, который показывает обратное состояние: определение зарегистрировано, решающих строк
 * нет ни у кого.
 *
 * <p>Проверяется не «строки появились», а два обещания C5.8. Первое: выдача говорит ровно то, что
 * решит сервер, — она строится из тех же реестров, покрывает плоскость сущностей и не трогает
 * существующие строки. Второе и главное: <b>lockout снимается</b> — до выдачи роль получает отказ
 * на canonical-запись типа без измерений (предсказание C5.4), после выдачи та же запись проходит.
 * Отчёт при этом согласован с выдачей: что выдача материализует, то из отчёта исчезает.</p>
 *
 * <p>Класс транзакционный: строки грантов и созданные сущности откатываются вместе с тестом,
 * поэтому проверка не оставляет следов в тестовой базе и не зависит от порядка классов.</p>
 */
@SpringBootTest
@Transactional
class InitialGrantIssuanceIT {

    private static final String RENAME_KEY =
        C5PermissionKeys.action(AttributeValuePermissions.RENAME_PERMISSION_ID);

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private SecurityContextHolderStrategy securityContextHolderStrategy;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private C5OperationPermissionRegistry operationDefinitions;

    @Autowired
    private C5ReadPermissionRegistry readDefinitions;

    @Autowired
    private AccessGrantAdminService accessGrantAdminService;

    @Autowired
    private EntityDataAccess access;

    @Autowired
    private UserService users;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @jakarta.persistence.PersistenceContext
    private EntityManager entityManager;

    /** Условный bean: без свойства его нет — проверка ниже закрепляет именно это. */
    @Autowired(required = false)
    private InitialGrantIssuance runner;

    @AfterEach
    void tearDown() {
        securityContextHolderStrategy.clearContext();
        SecurityContextHolder.clearContext();
    }

    /** По умолчанию первичная выдача не выполняется: право выдаёт администратор, а не старт. */
    @Test
    void theIssuanceIsOffByDefault() {
        assertThat(runner)
            .as("без app.security-baseline.enabled=true выдача при старте не создаётся")
            .isNull();
    }

    /** Плоскость сущностей выдаётся целиком, идемпотентно и без чужих каналов. */
    @Test
    void theIssuanceMaterializesEntityDefinitionsExactlyOnce() {
        String roleName = role("issuance-writes");
        InitialGrantIssuance issuance = issuance(roleName);

        int issued = issuance.issueForRole(roleName);
        List<AccessGrant> rows = rowsOfRole(roleName);
        assertThat(issued).isEqualTo(rows.size()).isPositive();
        assertThat(rows)
            .filteredOn(row -> !row.getDimension().startsWith("ENTITY:"))
            .as("сверх плоскости сущностей выдаётся ровно административный минимум — операции,"
                + " названные владельцами; остальные ACTION/REST выдаются точечно")
            .extracting(AccessGrant::getDimension)
            .containsExactlyInAnyOrder(
                C5PermissionKeys.action(UserRoleAssignmentPermission.PERMISSION_ID),
                C5PermissionKeys.action(UserPasswordChangePermission.PERMISSION_ID));
        assertThat(rows)
            .filteredOn(row -> row.getDimension().startsWith("ENTITY:"))
            .as("плоскость сущностей выдана целиком")
            .isNotEmpty();
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getDimension())
                .isEqualTo(C5PermissionKeys.action(UserPasswordChangePermission.PERMISSION_ID));
            assertThat(row.isCanUpdate()).as("EXECUTE решается флагом «изменение»").isTrue();
            assertThat(row.isCanRead()).isFalse();
            assertThat(row.isCanDelete()).isFalse();
        });

        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getDimension())
                .isEqualTo(C5PermissionKeys.entityOperations(Nomenclature.class));
            assertThat(row.isCanUpdate()).as("CREATE решается флагом «изменение»").isTrue();
            assertThat(row.isCanDelete()).isTrue();
            assertThat(row.isCanRead())
                .as("одна ENTITY-строка содержит все объявленные флаги")
                .isTrue();
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getDimension())
                .isEqualTo(C5PermissionKeys.entityRead(Nomenclature.class));
            assertThat(row.isCanRead()).isTrue();
            assertThat(row.isCanUpdate()).isTrue();
            assertThat(row.isCanDelete()).isTrue();
        });

        int again = issuance.issueForRole(roleName);
        assertThat(again).as("повторный запуск не дублирует и не переписывает").isZero();
        assertThat(rowsOfRole(roleName)).hasSize(rows.size());
    }

    /** Существующая строка (в том числе с флагом, которого определение не выражает) не переписывается. */
    @Test
    void anExistingRowIsNeverRewritten() {
        String roleName = role("issuance-existing");
        String key = C5PermissionKeys.entityOperations(Role.class);
        AccessGrant legacy = new AccessGrant();
        legacy.setSubjectType(AccessGrant.SubjectType.ROLE);
        legacy.setSubjectKey(roleName);
        legacy.setDimension(key);
        legacy.setDimensionValueId(null);
        legacy.setCanRead(true);
        accessGrantRepository.saveAndFlush(legacy);

        issuance(roleName).issueForRole(roleName);

        assertThat(accessGrantRepository
            .findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
                AccessGrant.SubjectType.ROLE, roleName, key))
            .as("выдача добавляет отсутствующее, а не исправляет чужие строки молча")
            .singleElement()
            .satisfies(row -> {
                assertThat(row.isCanRead()).isTrue();
                assertThat(row.isCanUpdate()).isFalse();
                assertThat(row.isCanDelete()).isFalse();
            });
        assertThat(rowsOfRole(roleName).size()).isGreaterThan(1);
    }

    /** Главное обещание: до выдачи — отказ, после выдачи — та же запись проходит. */
    @Test
    void theIssuedBaselineLiftsTheLockoutForTheRole() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String setup = "issuance-setup-" + suffix;
        String roleName = "issuance-role-" + suffix;
        String username = "issuance-user-" + suffix;
        String target = "issuance-target-" + suffix;

        asAdmin(setup, () -> {
            RlsTestFixture.grantC5EntityRead(accessGrantRepository, setup, Role.class, User.class);
            RlsTestFixture.grantC5EntityOperations(accessGrantRepository, setup, Role.class, User.class);
            // Пароль учётной записи — отдельная операция (C5.8e): фикстура пишет canonical create,
            // поэтому строка выдана ей явно, как и остальные права.
            RlsTestFixture.grantC5Action(accessGrantRepository, setup,
                UserPasswordChangePermission.PERMISSION_ID);
            Role role = access.create(Role.class, new Role(roleName));
            User user = new User(username, "password");
            user.setRoles(Set.of(role));
            access.create(User.class, user);
        });

        loginAs(username);
        assertThatThrownBy(() -> access.create(Role.class, new Role(target)))
            .as("без явной строки операции canonical-запись закрыта даже владельцу роли (C5.4)")
            .isInstanceOf(C5AccessDeniedException.class)
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().reason())
                .isEqualTo(C5DecisionReason.ENTITY_PERMISSION_DENIED));

        assertThat(issuance(roleName).issueForRole(roleName)).isPositive();

        loginAs(username);
        Role created = access.create(Role.class, new Role(target));
        assertThat(created.getId()).as("первичная выдача снимает lockout: запись проходит")
            .isNotNull();
        assertThat(created.getName()).isEqualTo(target);
    }

    /**
     * Отчёт и выдача согласованы: зарегистрированное определение без решающих строк попадает в
     * отчёт, выдача (или выдача администратором) убирает его оттуда.
     */
    @Test
    void theReportClearsExactlyWhatIsIssued() {
        String roleName = role("issuance-report");
        String setup = role("issuance-report-setup");
        asAdmin(setup, () -> {
            RlsTestFixture.grantC5EntityRead(accessGrantRepository, setup, Role.class);
            RlsTestFixture.grantC5EntityOperations(accessGrantRepository, setup, Role.class);
            access.create(Role.class, new Role(roleName));
            entityManager.flush();
        });
        String operationsKey = C5PermissionKeys.entityOperations(Nomenclature.class);
        String readKey = C5PermissionKeys.entityRead(Nomenclature.class);
        deleteAllRowsFor(operationsKey);
        deleteAllRowsFor(readKey);
        deleteAllRowsFor(RENAME_KEY);

        String admin = "issuance-admin-" + UUID.randomUUID().toString().substring(0, 8);
        asAdmin(admin, () -> assertThat(ungrantedKeys())
            .as("определение зарегистрировано, решающих строк нет ни у кого — оно обязано быть в отчёте")
            .contains(operationsKey, readKey, RENAME_KEY));

        assertThat(issuance(roleName).issueForRole(roleName)).isPositive();
        asAdmin(admin, () -> assertThat(ungrantedKeys())
            .as("первичная выдача материализует определения сущностей")
            .doesNotContain(operationsKey, readKey)
            .contains(RENAME_KEY));

        asAdmin(admin, () -> accessGrantAdminService.saveSingleGrant(RENAME_KEY,
            AccessGrant.SubjectType.ROLE, roleName, new GrantFlags(false, true, false)));
        asAdmin(admin, () -> assertThat(ungrantedKeys())
            .as("точечная выдача администратором убирает из отчёта именованную операцию")
            .doesNotContain(RENAME_KEY));
    }

    /**
     * C5.8e: роль базовой выдачи управляет пользователями без ручной выдачи каждого ключа.
     *
     * <p>Проверяется не «строки появились», а рабочий сценарий целиком. Состояний три, и разница
     * между ними — суть пункта. Роль без единой строки отказывает на создании учётной записи
     * (пароль спрашивается раньше любого чтения). Роль, которая <b>уже меняет пользователей
     * generic-записью</b>, но названных операций не имеет, отказывает на обоих действиях именно
     * <b>своими операциями</b>: право изменить строку пользователя не даёт ни сменить его пароль,
     * ни распорядиться его ролями. После первичной выдачи те же два действия проходят без единой
     * ручной выдачи, а <b>самоповышение остаётся закрытым</b> — право на операцию и право
     * расширить свою учётную запись остаются разными вещами (П13).</p>
     *
     * <p>Почему generic-строки по {@code User} выдаются фикстурой заранее: назначение ролей
     * сравнивает набор с сохранённым, то есть читает цель. Не выдав чтения, мы наблюдали бы отказ
     * чтения, а не отказ операции, — и проверка утверждала бы не то, что называет. Выданное чтение
     * и есть то состояние, в котором вопрос «почему отказ» имеет единственный честный ответ:
     * строки операции нет.</p>
     */
    @Test
    void theIssuedUserAdministrationOperationsLetTheRoleManageUsers() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String setup = "issuance-users-setup-" + suffix;
        String roleName = "issuance-users-role-" + suffix;
        String memberRole = "issuance-users-member-" + suffix;
        String actorName = "issuance-users-admin-" + suffix;
        String victimName = "issuance-users-victim-" + suffix;
        String createdName = "issuance-users-created-" + suffix;

        asAdmin(setup, () -> {
            RlsTestFixture.grantC5EntityRead(accessGrantRepository, setup, Role.class, User.class);
            RlsTestFixture.grantC5EntityOperations(accessGrantRepository, setup, Role.class, User.class);
            // Пароль учётной записи — отдельная операция (C5.8e): фикстура пишет canonical create,
            // поэтому строка выдана ей явно, а не подразумевается «раз это setup».
            RlsTestFixture.grantC5Action(accessGrantRepository, setup,
                UserPasswordChangePermission.PERMISSION_ID);
            Role role = access.create(Role.class, new Role(roleName));
            Role member = access.create(Role.class, new Role(memberRole));
            User actor = new User(actorName, "password-value");
            actor.setRoles(Set.of(role));
            access.create(User.class, actor);
            User victim = new User(victimName, "password-value");
            victim.addRole(member);
            access.create(User.class, victim);

            // Роль актора получает generic read+write по User — «роль уже меняет пользователей».
            // Названных операций у неё нет: ни пароля, ни ролей. Обе строки выданы явно, чтобы
            // отказ назвал операцию, а не невидимость строки (см. javadoc метода).
            accessGrantAdminService.saveSingleGrant(C5PermissionKeys.entity(User.class),
                AccessGrant.SubjectType.ROLE, roleName, new GrantFlags(true, true, false));
        });

        loginAs(actorName);
        User requested = new User(createdName, null);
        requested.setRawPassword("created-secret-value");
        requested.addRole(roleRepository.findByName(memberRole).orElseThrow());
        assertThatThrownBy(() -> users.save(requested))
            .as("создание учётной записи закрыто: generic-запись User пароль не несёт")
            .isInstanceOf(C5AccessDeniedException.class)
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().key())
                .isEqualTo(C5PermissionKeys.action(UserPasswordChangePermission.PERMISSION_ID)));

        User victim = userRepository.findByUsername(victimName).orElseThrow();
        User victimCopy = copyWithAddedRoles(victim,
            roleRepository.findByName(roleName).orElseThrow());
        assertThatThrownBy(() -> users.save(victimCopy))
            .as("право изменить строку пользователя не даёт распорядиться его ролями")
            .isInstanceOf(C5AccessDeniedException.class)
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().key())
                .isEqualTo(C5PermissionKeys.action(UserRoleAssignmentPermission.PERMISSION_ID)));
        assertThat(persistedRoleNames(victimName))
            .as("отказ операции не оставляет частичной записи")
            .containsExactly(memberRole);

        assertThat(issuance(roleName).issueForRole(roleName)).isPositive();

        loginAs(actorName);
        User created = new User(createdName, null);
        created.setRawPassword("created-secret-value");
        created.addRole(roleRepository.findByName(memberRole).orElseThrow());
        users.save(created);

        User managed = userRepository.findByUsername(createdName).orElseThrow();
        assertThat(managed.getId())
            .as("первичная выдача сняла lockout: администратор управляет пользователями сам")
            .isNotNull();
        assertThat(managed.getPassword()).isNotEqualTo("created-secret-value");
        assertThat(persistedRoleNames(createdName)).containsExactly(memberRole);

        User victimReloaded = userRepository.findByUsername(victimName).orElseThrow();
        users.save(copyWithAddedRoles(victimReloaded,
            roleRepository.findByName(roleName).orElseThrow()));
        assertThat(persistedRoleNames(victimName))
            .as("назначение ролей чужой учётной записи проходит по выданной строке")
            .containsExactlyInAnyOrder(memberRole, roleName);

        User own = userRepository.findByUsername(actorName).orElseThrow();
        assertThatThrownBy(() -> users.save(copyWithAddedRoles(own,
            roleRepository.findByName(memberRole).orElseThrow())))
            .as("выданное право на операцию не открывает расширение своей учётной записи (П13)")
            .isInstanceOf(AccessDeniedException.class)
            .isNotInstanceOf(C5AccessDeniedException.class);
        assertThat(persistedRoleNames(actorName)).containsExactly(roleName);
    }

    /** Названный, но незарегистрированный ключ административного минимума — отказ, а не пропуск. */
    @Test
    void anUnknownOperationKeyStopsTheIssuance() {
        String roleName = role("issuance-unknown");
        InitialGrantIssuance issuance = new InitialGrantIssuance(accessGrantRepository,
            operationDefinitions, readDefinitions, List.of(roleName),
            List.of("USER:NO_SUCH_OPERATION"));

        assertThatThrownBy(() -> issuance.issueForRole(roleName))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("USER:NO_SUCH_OPERATION");
    }

    // === Фикстура ===

    /**
     * Копия строки с добавленными ролями: сохраняется экземпляр, которого нет в живой сессии, —
     * иначе canonical read вернул бы уже изменённый управляемый экземпляр и сравнение наборов
     * проверяло бы значение с самим собой.
     */
    private User copyWithAddedRoles(User target, Role... added) {
        User copy = new User(target.getUsername(), target.getPassword());
        copy.setId(target.getId());
        copy.setVersion(target.getVersion());
        copy.setEnabled(target.isEnabled());
        for (Role role : target.getRoles()) {
            copy.addRole(role);
        }
        for (Role role : added) {
            copy.addRole(role);
        }
        return copy;
    }

    /** Набор ролей читается после flush/clear: иначе утверждение проверяло бы кэш сессии. */
    private Set<String> persistedRoleNames(String username) {
        entityManager.flush();
        entityManager.clear();
        return userRepository.findByUsername(username).orElseThrow().getRoles().stream()
            .map(Role::getName)
            .collect(Collectors.toSet());
    }

    private InitialGrantIssuance issuance(String roleName) {
        return new InitialGrantIssuance(accessGrantRepository, operationDefinitions, readDefinitions,
            List.of(roleName), List.of(UserRoleAssignmentPermission.PERMISSION_ID,
                UserPasswordChangePermission.PERMISSION_ID));
    }

    private List<String> ungrantedKeys() {
        return accessGrantAdminService.ungrantedDefinitions().stream()
            .map(AccessGrantAdminService.UngrantedDefinition::key)
            .toList();
    }

    private List<AccessGrant> rowsOfRole(String roleName) {
        return accessGrantRepository.findAll().stream()
            .filter(row -> row.getSubjectType() == AccessGrant.SubjectType.ROLE
                && roleName.equals(row.getSubjectKey()))
            .toList();
    }

    private void deleteAllRowsFor(String dimension) {
        accessGrantRepository.deleteAll(accessGrantRepository.findAll().stream()
            .filter(row -> dimension.equals(row.getDimension()))
            .toList());
        accessGrantRepository.flush();
    }

    private static String role(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void asAdmin(String subject, Runnable action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext previousMethodContext = securityContextHolderStrategy.getContext();
        SecurityContext admin = securityContextHolderStrategy.createEmptyContext();
        admin.setAuthentication(new UsernamePasswordAuthenticationToken(subject, "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        securityContextHolderStrategy.setContext(admin);
        SecurityContextHolder.setContext(admin);
        try {
            action.run();
        } finally {
            securityContextHolderStrategy.setContext(previousMethodContext);
            SecurityContextHolder.setContext(previous);
        }
    }

    private void loginAs(String username) {
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
        securityContextHolderStrategy.setContext(context);
        SecurityContextHolder.setContext(context);
    }
}
