package org.ip.security;

import org.ip.persistence.access.PermissionSubjectLock;
import org.ip.application.access.SubjectRef;
import org.ip.application.user.UserPasswordChangePermission;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.Role;
import org.ip.model.User;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.ip.service.AccessGrantAdminService;
import org.ip.service.AccessGrantAdminService.GrantFlags;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.c5.C5PermissionEvaluator;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * F-REST-READ-3, З2: матрица приёмки «admin C5 decision == server C5 decision» на настоящем
 * provider/storage, реальных User/Role и действующем admin service. Каждое сохранение и отзыв
 * проверяется тем же серверным evaluator'ом, который применяет read pipeline.
 */
@SpringBootTest
class C5GrantAdminParityIT {

    private static final String C5_KEY = C5PermissionKeys.entityRead(Nomenclature.class);
    private static final String LEGACY_CHECK_ONLY = "ENTITY:ReceivingDocument";

    @Autowired
    private SecurityContextHolderStrategy securityContextHolderStrategy;

    @Autowired
    private AccessGrantAdminService accessGrantAdminService;

    @Autowired
    private C5PermissionEvaluator c5PermissionEvaluator;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    /** Canonical write handle для фикстур `Role`/`User`: тот же preflight, что у приложения. */
    @Autowired
    private org.ipro.data.EntityDataAccess access;

    @MockitoBean
    private DataInitializer dataInitializer;

    /** Субъекты фикстуры синтетические: lock отвечает стабильной ссылкой, не читая строку. */
    @MockitoBean
    private PermissionSubjectLock permissionSubjectLock;

    @BeforeEach
    void authenticateAdmin() {
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("admin", "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        securityContextHolderStrategy.setContext(context);
        SecurityContextHolder.setContext(context);
        doAnswer(call -> {
            SubjectRef.Identity identity = call.getArgument(0);
            return new SubjectRef(identity.type(), identity.id(), "fixture-subject", "fixture-subject");
        }).when(permissionSubjectLock).acquire(any(SubjectRef.Identity.class));
    }

    @AfterEach
    void clearAuthentication() {
        securityContextHolderStrategy.clearContext();
        SecurityContextHolder.clearContext();
    }

    /**
     * Права, которых требует canonical write: read-ключ и ключ операций — разные строки, и
     * отдельной строкой — смена пароля.
     *
     * <p>Запись {@code User} вместе с хэшем пароля — не generic-изменение строки, а именованная
     * операция владельца (C5.8e): фикстура предъявляет её так же, как первичная выдача
     * администратору. Без неё создание пользователя отказывает — это и проверяет
     * {@code UserPasswordWriteBoundaryIT}; здесь она нужна, потому что фикстура создаёт
     * пользователя, а не потому что тест проверяет пароль.</p>
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

    @Test
    void wildcardGrantDoesNotAuthorizeC5ReadButKeepsLegacyRlsSemantics() {
        String username = "c5-parity-wildcard";
        accessGrantRepository.saveAndFlush(grant(AccessGrant.SubjectType.USER, username, "*", null,
            true, true, true));

        assertThat(c5PermissionEvaluator.isEntityReadPermitted(username, Nomenclature.class)).isFalse();
        AccessService.EffectiveGrant c5 = effective(username).get(C5_KEY);
        assertThat(c5.canRead()).isFalse();
        assertThat(c5.canUpdate()).isFalse();
        assertThat(c5.canDelete()).isFalse();

        // RLS wildcard продолжает действовать для обычных измерений.
        assertThat(effective(username).get("JOURNAL").canRead()).isTrue();
        assertThat(effective(username).get("JOURNAL").unlimited()).isTrue();
    }

    @Test
    void saveAndRevokeThroughAdminIsImmediatelyVisibleToServer() {
        String username = "c5-parity-direct";
        accessGrantAdminService.saveSingleGrant(C5_KEY, AccessGrant.SubjectType.USER, username,
            new GrantFlags(true, false, false));

        assertThat(c5PermissionEvaluator.isEntityReadPermitted(username, Nomenclature.class)).isTrue();
        assertThat(accessGrantAdminService.currentSingleGrant(C5_KEY, AccessGrant.SubjectType.USER, username))
            .isEqualTo(new GrantFlags(true, false, false));
        AccessService.EffectiveGrant granted = effective(username).get(C5_KEY);
        assertThat(granted.canRead()).isTrue();
        assertThat(granted.canUpdate()).isFalse();
        assertThat(granted.canDelete()).isFalse();
        assertThat(granted.sources()).contains("прямой");

        accessGrantAdminService.saveSingleGrant(C5_KEY, AccessGrant.SubjectType.USER, username,
            GrantFlags.NONE);
        assertThat(c5PermissionEvaluator.isEntityReadPermitted(username, Nomenclature.class)).isFalse();
        assertThat(effective(username).get(C5_KEY).canRead()).isFalse();
    }

    @Test
    void roleGrantIsEffectiveOnlyWhileRoleIsAssigned() {
        String username = "c5-parity-role-user";
        String roleName = "c5-parity-role";
        // Первичная выдача: собственный субъект теста получает право на canonical write (ADR-0017 §3).
        grantCanonicalWrite("admin", Role.class, User.class);
        Role role = access.create(Role.class, new Role(roleName));
        User user = new User(username, "password");
        user.setRoles(Set.of(role));
        access.create(User.class, user);

        accessGrantAdminService.saveSingleGrant(C5_KEY, AccessGrant.SubjectType.ROLE, roleName,
            new GrantFlags(true, false, false));

        assertThat(c5PermissionEvaluator.isEntityReadPermitted(username, Nomenclature.class)).isTrue();
        AccessService.EffectiveGrant viaRole = effective(username).get(C5_KEY);
        assertThat(viaRole.canRead()).isTrue();
        assertThat(viaRole.sources()).containsExactly("роль: " + roleName);

        user.setRoles(Set.of());
        access.update(User.class, user);
        assertThat(c5PermissionEvaluator.isEntityReadPermitted(username, Nomenclature.class)).isFalse();
        assertThat(effective(username).get(C5_KEY).canRead()).isFalse();

        // Роль без назначения пользователю не авторизует его, но её собственное решение — allow.
        assertThat(c5PermissionEvaluator.isEntityReadPermitted("c5-parity-unassigned-user",
            Nomenclature.class)).isFalse();
        assertThat(accessGrantAdminService.collectEffective(AccessGrant.SubjectType.ROLE, roleName)
            .get(C5_KEY).canRead()).isTrue();
    }

    @Test
    void valueScopedOnlyRowDoesNotBecomeGlobalC5Read() {
        String username = "c5-parity-valued";
        accessGrantRepository.saveAndFlush(grant(AccessGrant.SubjectType.USER, username, C5_KEY, 42L,
            true, false, false));

        assertThat(c5PermissionEvaluator.isEntityReadPermitted(username, Nomenclature.class)).isFalse();
        assertThat(effective(username).get(C5_KEY).canRead()).isFalse();
        assertThat(accessGrantAdminService.currentSingleGrant(C5_KEY, AccessGrant.SubjectType.USER, username))
            .isEqualTo(GrantFlags.NONE);
    }

    @Test
    void updateOnlyGlobalRowDoesNotGrantReadButShowsItsIndependentWriteFlags() {
        String username = "c5-parity-update-only";
        accessGrantRepository.saveAndFlush(grant(AccessGrant.SubjectType.USER, username, C5_KEY, null,
            false, true, true));

        assertThat(c5PermissionEvaluator.isEntityReadPermitted(username, Nomenclature.class)).isFalse();
        assertThat(effective(username).get(C5_KEY).canRead()).isFalse();
        assertThat(accessGrantAdminService.currentSingleGrant(C5_KEY, AccessGrant.SubjectType.USER, username))
            .isEqualTo(new GrantFlags(false, true, true));
    }

    @Test
    void c5SaveAcceptsIndependentWriteFlagsButRejectsValueScopedInput() {
        String username = "c5-parity-boundary";
        AccessGrant valueRow = grant(AccessGrant.SubjectType.USER, username, C5_KEY, 7L, true, false, false);
        accessGrantRepository.saveAndFlush(valueRow);

        accessGrantAdminService.saveSingleGrant(C5_KEY, AccessGrant.SubjectType.USER, username,
            new GrantFlags(false, true, true));
        assertThat(accessGrantAdminService.currentSingleGrant(C5_KEY, AccessGrant.SubjectType.USER, username))
            .isEqualTo(new GrantFlags(false, true, true));
        assertThatThrownBy(() -> accessGrantAdminService.saveGrants(C5_KEY,
            AccessGrant.SubjectType.USER, username, java.util.Map.of(7L, new GrantFlags(true, false, false))))
            .isInstanceOf(IllegalArgumentException.class);

        accessGrantAdminService.saveSingleGrant(C5_KEY, AccessGrant.SubjectType.USER, username,
            GrantFlags.NONE);
        assertThat(accessGrantRepository.findBySubjectTypeAndSubjectKeyAndDimension(
            AccessGrant.SubjectType.USER, username, C5_KEY))
            .extracting(AccessGrant::getDimensionValueId)
            .containsExactly(7L);
    }

    /**
     * C5.7: короткий {@code ENTITY:}-ключ — ключ операций, и выдача пишет ровно объявленные флаги.
     * Раньше строка «изменение» нормализовала «чтение», потому что этот же флаг служил гейтом
     * видимости плитки; теперь плитку и чтение типа решает C5 read-ключ {@code ENTITY:<FQN>},
     * поэтому флаг чтения на операционном ключе не записывается и никого не вводит в заблуждение.
     */
    @Test
    void oldShortEntityKeyIsNeitherRegisteredNorAnEffectiveEntityRight() {
        String username = "c5-parity-legacy";
        assertThat(accessGrantAdminService.isOperationDimension(LEGACY_CHECK_ONLY)).isFalse();
        assertThat(accessGrantAdminService.isC5ReadDimension(LEGACY_CHECK_ONLY)).isFalse();

        accessGrantRepository.saveAndFlush(grant(AccessGrant.SubjectType.USER, username, LEGACY_CHECK_ONLY,
            null, true, true, true));

        assertThat(effective(username)).doesNotContainKey(LEGACY_CHECK_ONLY);
        assertThat(c5PermissionEvaluator.isEntityReadPermitted(username, org.ip.model.ReceivingDocument.class)).isFalse();
    }

    private java.util.Map<String, AccessService.EffectiveGrant> effective(String username) {
        return accessGrantAdminService.collectEffective(AccessGrant.SubjectType.USER, username);
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
