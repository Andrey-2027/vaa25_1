package org.ip.security;

import org.ip.application.catalog.AttributeValuePermissions;
import org.ip.application.user.UserPasswordChangePermission;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.Role;
import org.ip.model.User;
import org.ip.service.AccessGrantAdminService;
import org.ip.service.AccessGrantAdminService.GrantFlags;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Эффективные права по <b>операционному</b> измерению берутся решением C5, а не RLS-свёрткой.
 *
 * <p>Диалог «Эффективные права» — это ответ на вопрос «что в итоге получит субъект». Пока для
 * ключей операций он складывался из {@link AccessService#effectiveGrants}, он отвечал <b>не</b>
 * тем движком, которым решает сервер: свёртка читает гранты вместе с wildcard {@code "*"} и
 * учитывает value-scoped строки, а решение операции — нет (ADR-0017 §3). Держатель одной
 * wildcard-строки видел в диалоге «Изменение: Да», тогда как canonical write и именованная
 * операция отказывали.</p>
 *
 * <p>Здесь проверяется не «столбик появился», а пара: <b>показанное решение совпадает с тем,
 * которое исполнит сервер</b> — и там, где право есть, и там, где свёртка его выдумывала. Отдельно
 * закреплено, что короткий {@code ENTITY:<Имя>}-ключ — ключ операций во всех столбцах: с C5.7
 * его флаг чтения больше не читает никто (ни плитка подсистемы, ни гейт чтения типа), и чтение
 * сущности живёт отдельной строкой read-ключа {@code ENTITY:<FQN>}.</p>
 */
@SpringBootTest
class OperationEffectiveRightsIT {

    private static final String RENAME_KEY =
        C5PermissionKeys.action(AttributeValuePermissions.RENAME_PERMISSION_ID);
    private static final String NOMENCLATURE_OPERATIONS =
        C5PermissionKeys.entityOperations(Nomenclature.class);

    private final List<String> subjects = new ArrayList<>();

    @Autowired
    private SecurityContextHolderStrategy securityContextHolderStrategy;

    @Autowired
    private AccessGrantAdminService accessGrantAdminService;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private AccessService accessService;

    @Autowired
    private C5PermissionDecisionService c5PermissionDecisionService;

    @Autowired
    private org.ipro.data.EntityDataAccess access;

    @MockitoBean
    private DataInitializer dataInitializer;

    @BeforeEach
    void authenticateAdmin() {
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("admin", "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        securityContextHolderStrategy.setContext(context);
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void tearDown() {
        for (String subject : subjects) {
            for (String dimension : List.of("*", RENAME_KEY, NOMENCLATURE_OPERATIONS,
                    C5PermissionKeys.entityRead(Nomenclature.class))) {
                accessGrantRepository.deleteAll(accessGrantRepository
                    .findBySubjectTypeAndSubjectKeyAndDimension(
                        AccessGrant.SubjectType.USER, subject, dimension));
                accessGrantRepository.deleteAll(accessGrantRepository
                    .findBySubjectTypeAndSubjectKeyAndDimension(
                        AccessGrant.SubjectType.ROLE, subject, dimension));
            }
        }
        accessGrantRepository.flush();
        subjects.clear();
        securityContextHolderStrategy.clearContext();
        SecurityContextHolder.clearContext();
    }

    /** Выданное право показано так же, как оно решается: выдача, отображение и отзыв — один ответ. */
    @Test
    void theDialogShowsTheSameDecisionTheServerEnforcesForANamedOperation() {
        String username = subject("op-eff-direct");
        accessGrantAdminService.saveSingleGrant(RENAME_KEY, AccessGrant.SubjectType.USER, username,
            new GrantFlags(false, true, false));

        assertThat(screen(username).get(RENAME_KEY))
            .extracting(AccessService.EffectiveGrant::canRead, AccessService.EffectiveGrant::canUpdate,
                AccessService.EffectiveGrant::canDelete)
            .containsExactly(false, true, false);
        assertThat(screen(username).get(RENAME_KEY).sources()).containsExactly("прямой");
        assertThat(c5PermissionDecisionService
            .decideAction(username, AttributeValuePermissions.RENAME_PERMISSION_ID).allowed())
            .as("экран и сервер обязаны отвечать одинаково на выданное право")
            .isTrue();

        accessGrantAdminService.saveSingleGrant(RENAME_KEY, AccessGrant.SubjectType.USER, username,
            GrantFlags.NONE);

        assertThat(screen(username).get(RENAME_KEY).canUpdate()).isFalse();
        assertThat(screen(username).get(RENAME_KEY).sources()).isEmpty();
        assertThat(c5PermissionDecisionService
            .decideAction(username, AttributeValuePermissions.RENAME_PERMISSION_ID).allowed())
            .as("отозванное право обязано исчезнуть и с экрана, и из решения")
            .isFalse();
    }

    /**
     * Wildcard-строка: свёртка говорила «Да», решение операции отказывает. Показано обязано быть
     * решение — иначе администратор видел бы право, которого у субъекта нет.
     */
    @Test
    void aWildcardGrantIsNotShownAsANamedOperationRight() {
        String username = subject("op-eff-wildcard");
        accessGrantRepository.saveAndFlush(grant(AccessGrant.SubjectType.USER, username, "*", null,
            true, true, true));

        assertThat(accessService.effectiveGrants(RENAME_KEY, username).canUpdate())
            .as("свёртка, которой диалог пользовался раньше, wildcard учитывает — это и было расхождение")
            .isTrue();

        AccessService.EffectiveGrant shown = screen(username).get(RENAME_KEY);
        assertThat(shown.canRead()).isFalse();
        assertThat(shown.canUpdate()).isFalse();
        assertThat(shown.canDelete()).isFalse();
        assertThat(shown.sources()).isEmpty();
        assertThat(c5PermissionDecisionService
            .decideAction(username, AttributeValuePermissions.RENAME_PERMISSION_ID).allowed())
            .as("показанное решение обязано совпасть с серверным отказом")
            .isFalse();
    }

    /** Value-scoped строка — тот же случай: свёртка её видит, операция ею не решается. */
    @Test
    void aValueScopedRowIsNotShownAsANamedOperationRight() {
        String username = subject("op-eff-valued");
        accessGrantRepository.saveAndFlush(grant(AccessGrant.SubjectType.USER, username, RENAME_KEY,
            42L, true, true, true));

        assertThat(accessService.effectiveGrants(RENAME_KEY, username).canUpdate()).isTrue();

        AccessService.EffectiveGrant shown = screen(username).get(RENAME_KEY);
        assertThat(shown.canUpdate()).isFalse();
        assertThat(shown.canDelete()).isFalse();
        assertThat(shown.sources()).isEmpty();
        assertThat(c5PermissionDecisionService
            .decideAction(username, AttributeValuePermissions.RENAME_PERMISSION_ID).allowed())
            .isFalse();
    }

    /**
     * Роль показывается источником ровно пока она назначена: назначенная — разрешает (и решение
     * пользователя, и экран), снятая — не разрешает уже ни того, ни другого. Собственное решение
     * самой роли берётся тем же движком.
     */
    @Test
    void aRoleGrantIsShownOnlyWhileTheRoleIsAssigned() {
        String username = subject("op-eff-role-user");
        String roleName = subject("op-eff-role");
        grantCanonicalWrite("admin", Role.class, User.class);
        Role role = access.create(Role.class, new Role(roleName));
        User user = new User(username, "password");
        user.setRoles(Set.of(role));
        access.create(User.class, user);

        accessGrantAdminService.saveSingleGrant(RENAME_KEY, AccessGrant.SubjectType.ROLE, roleName,
            new GrantFlags(false, true, false));

        assertThat(screen(username).get(RENAME_KEY).canUpdate()).isTrue();
        assertThat(screen(username).get(RENAME_KEY).sources()).containsExactly("роль: " + roleName);
        assertThat(screenForRole(roleName).get(RENAME_KEY).canUpdate())
            .as("эффективные права роли — её собственное решение, а не свёртка чужих ролей")
            .isTrue();
        assertThat(c5PermissionDecisionService
            .decideAction(username, AttributeValuePermissions.RENAME_PERMISSION_ID).allowed())
            .isTrue();

        user.setRoles(Set.of());
        access.update(User.class, user);

        assertThat(screen(username).get(RENAME_KEY).canUpdate()).isFalse();
        assertThat(screen(username).get(RENAME_KEY).sources()).isEmpty();
        assertThat(c5PermissionDecisionService
            .decideAction(username, AttributeValuePermissions.RENAME_PERMISSION_ID).allowed())
            .as("роль без назначения не авторизует пользователя — и экран не показывает её источником")
            .isFalse();
        assertThat(screenForRole(roleName).get(RENAME_KEY).canUpdate())
            .as("у неназначенной роли остаётся её собственное решение — оно и показано")
            .isTrue();
    }

    /**
     * Короткий {@code ENTITY:<Имя>}-ключ — ключ операций во всех столбцах: «Изменение»/«Удаление»
     * решает C5, а столбец «Чтение» — тоже ответ C5, а не флаг строки: чтение сущности живёт на
     * отдельном read-ключе {@code ENTITY:<FQN>}. Ни плитка подсистемы, ни гейт чтения типа флаг
     * короткого ключа больше не спрашивают, поэтому и диалог его не показывает.
     */
    @Test
    void theShortEntityKeyIsAnOperationKeyInEveryColumn() {
        assertThat(accessGrantAdminService.isOperationDimension(NOMENCLATURE_OPERATIONS))
            .as("тип с canonical write handle обязан иметь определение операций")
            .isTrue();
        assertThat(accessGrantAdminService.isNamedOperationDimension(NOMENCLATURE_OPERATIONS)).isFalse();

        String wildcardOnly = subject("op-eff-entity-wildcard");
        accessGrantRepository.saveAndFlush(grant(AccessGrant.SubjectType.USER, wildcardOnly, "*", null,
            true, true, true));

        AccessService.EffectiveGrant shown = screen(wildcardOnly).get(NOMENCLATURE_OPERATIONS);
        assertThat(shown.canRead()).isFalse();
        assertThat(shown.canUpdate())
            .as("wildcard не авторизует операцию: столбцы обязаны совпасть с решением C5")
            .isFalse();
        assertThat(shown.canDelete()).isFalse();
        assertThat(c5PermissionDecisionService
            .decideEntity(wildcardOnly, Nomenclature.class, C5Operation.UPDATE).allowed())
            .isFalse();

        String granted = subject("op-eff-entity-granted");
        accessGrantAdminService.saveSingleGrant(NOMENCLATURE_OPERATIONS, AccessGrant.SubjectType.USER,
            granted, new GrantFlags(false, true, false));

        AccessService.EffectiveGrant grantedShown = screen(granted).get(NOMENCLATURE_OPERATIONS);
        assertThat(grantedShown.canRead())
            .as("чтение сущности решает read-ключ, а не флаг операционного ключа")
            .isFalse();
        assertThat(grantedShown.canUpdate()).isTrue();
        assertThat(grantedShown.canDelete()).isFalse();
        assertThat(c5PermissionDecisionService
            .decideEntity(granted, Nomenclature.class, C5Operation.UPDATE).allowed())
            .as("экран и сервер обязаны совпасть и на коротком ENTITY-ключе")
            .isTrue();
        assertThat(c5PermissionDecisionService
            .decideEntity(granted, Nomenclature.class, C5Operation.DELETE).allowed())
            .isFalse();
        assertThat(c5PermissionDecisionService.decideEntity(granted, Nomenclature.class, C5Operation.READ).allowed())
            .as("чтение задаётся независимым флагом той же ENTITY-строки")
            .isFalse();
    }

    // === Фикстура ===

    private Map<String, AccessService.EffectiveGrant> screen(String subjectKey) {
        return accessGrantAdminService.collectEffective(AccessGrant.SubjectType.USER, subjectKey);
    }

    private Map<String, AccessService.EffectiveGrant> screenForRole(String roleName) {
        return accessGrantAdminService.collectEffective(AccessGrant.SubjectType.ROLE, roleName);
    }

    private String subject(String prefix) {
        String subject = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        subjects.add(subject);
        return subject;
    }

    /**
     * Права, которых требует canonical write: read-ключ и ключ операций — разные строки, и
     * отдельной строкой — смена пароля.
     *
     * <p>Фикстура создаёт пользователя с хэшем, а это именованная операция владельца (C5.8e), а не
     * generic-изменение строки: без её строки создание отказывает. Тест проверяет не пароль, но
     * пользователя создать обязан — и предъявляет право явно, а не через ослабление правила.</p>
     */
    private void grantCanonicalWrite(String subject, Class<?>... types) {
        for (Class<?> type : types) {
            accessGrantAdminService.saveSingleGrant(C5PermissionKeys.entity(type),
                AccessGrant.SubjectType.USER, subject, new GrantFlags(true, true, true));
        }
        accessGrantAdminService.saveSingleGrant(
            C5PermissionKeys.action(UserPasswordChangePermission.PERMISSION_ID),
            AccessGrant.SubjectType.USER, subject, new GrantFlags(false, true, false));
    }

    private static AccessGrant grant(AccessGrant.SubjectType subjectType, String subjectKey,
                                     String dimension, Long valueId,
                                     boolean read, boolean update, boolean delete) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(subjectType);
        grant.setSubjectKey(subjectKey);
        grant.setDimension(dimension);
        grant.setDimensionValueId(valueId);
        grant.setCanRead(read);
        grant.setCanUpdate(update);
        grant.setCanDelete(delete);
        return grant;
    }
}
