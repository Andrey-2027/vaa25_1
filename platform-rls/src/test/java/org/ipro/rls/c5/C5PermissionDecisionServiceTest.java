package org.ipro.rls.c5;

import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsRoleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** ADR-0017 §1, §3, §8, §10: one C5 decision for entity operations and named operations. */
class C5PermissionDecisionServiceTest {

    private static final String READ_KEY = C5PermissionKeys.entityRead(String.class);
    private static final String OPERATION_KEY = C5PermissionKeys.entityOperations(String.class);

    private AccessGrantRepository grantRepository;
    private AtomicReference<List<String>> roles;
    private C5OperationPermissionRegistry operationRegistry;
    private C5PermissionDecisionService service;

    @BeforeEach
    void setUp() {
        grantRepository = Mockito.mock(AccessGrantRepository.class);
        roles = new AtomicReference<>(List.of());
        RlsRoleResolver roleResolver = username -> roles.get();
        operationRegistry = new C5OperationPermissionRegistry();
        operationRegistry.register(C5PermissionDefinition.entityOperations(String.class,
            Set.of(C5Operation.CREATE, C5Operation.UPDATE, C5Operation.DELETE)));
        operationRegistry.register(C5PermissionDefinition.entityRead(String.class));
        operationRegistry.register(C5PermissionDefinition.action("RegisterWrite"));
        service = new C5PermissionDecisionService(grantRepository, roleResolver, operationRegistry,
            new C5ReadDecisionService(grantRepository, roleResolver));
    }

    private void exact(String subjectType, String subjectKey, String key, AccessGrant... grants) {
        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.valueOf(subjectType), subjectKey, key))
            .thenReturn(List.of(grants));
    }

    private static AccessGrant grant(boolean read, boolean update, boolean delete) {
        AccessGrant grant = new AccessGrant();
        grant.setCanRead(read);
        grant.setCanUpdate(update);
        grant.setCanDelete(delete);
        return grant;
    }

    @Test
    void canUpdateAllowsCreateAndUpdateAndExecuteButNotDelete() {
        exact("USER", "alice", OPERATION_KEY, grant(false, true, false));

        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.CREATE).allowed()).isTrue();
        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.UPDATE).allowed()).isTrue();
        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.DELETE).reason())
            .isEqualTo(C5DecisionReason.ENTITY_PERMISSION_DENIED);

        exact("USER", "alice", "ACTION:RegisterWrite", grant(false, true, false));
        assertThat(service.decideAction("alice", "RegisterWrite").allowed()).isTrue();
    }

    @Test
    void canDeleteAllowsDeleteOnly() {
        exact("USER", "alice", OPERATION_KEY, grant(false, false, true));

        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.DELETE).allowed()).isTrue();
        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.UPDATE).allowed()).isFalse();
        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.CREATE).allowed()).isFalse();
    }

    @Test
    void readGrantIsNeverMistakenForAWritePermission() {
        exact("USER", "alice", OPERATION_KEY, grant(true, false, false));
        exact("USER", "alice", READ_KEY, grant(true, false, false));

        assertThat(service.decideEntity("alice", String.class, C5Operation.READ).allowed()).isTrue();
        assertThat(service.decideEntity("alice", String.class, C5Operation.CREATE).allowed()).isFalse();
        assertThat(service.decideEntity("alice", String.class, C5Operation.UPDATE).allowed()).isFalse();
        assertThat(service.decideEntity("alice", String.class, C5Operation.DELETE).allowed()).isFalse();
    }

    @Test
    void writeFlagsAloneDoNotGrantRead() {
        exact("USER", "alice", OPERATION_KEY, grant(false, true, true));
        exact("USER", "alice", READ_KEY, grant(false, true, true));

        assertThat(service.decideEntity("alice", String.class, C5Operation.READ).allowed()).isFalse();
    }

    @Test
    void wildcardGrantDoesNotAuthorizeAnOperation() {
        AccessGrant wildcard = grant(false, true, true);
        wildcard.setDimension("*");
        when(grantRepository.findUserGrants(OPERATION_KEY, "alice")).thenReturn(List.of(wildcard));

        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.UPDATE).allowed()).isFalse();
        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.DELETE).allowed()).isFalse();
    }

    @Test
    void assignedRoleGrantAllowsAndReportsTheRoleSource() {
        exact("ROLE", "ROLE_ENGINEER", OPERATION_KEY, grant(false, true, false));
        roles.set(List.of("ROLE_ENGINEER"));

        C5PermissionDecision decision = service.decide("alice", OPERATION_KEY, C5Operation.UPDATE);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo(C5DecisionReason.PERMITTED);
        assertThat(decision.sources()).containsExactly("роль: ROLE_ENGINEER");
        assertThat(decision.message()).isEqualTo("Разрешено");
    }

    @Test
    void roleWithoutAssignmentDoesNotAuthorizeAnOperation() {
        exact("ROLE", "ROLE_ENGINEER", OPERATION_KEY, grant(false, true, true));
        roles.set(List.of());

        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.UPDATE).allowed()).isFalse();
    }

    @Test
    void directGrantIsReportedAsDirectSource() {
        exact("USER", "alice", OPERATION_KEY, grant(false, true, false));

        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.CREATE).sources())
            .containsExactly("прямой");
    }

    @Test
    void readDelegatesToTheExistingReadDecisionWithItsSources() {
        exact("USER", "alice", READ_KEY, grant(true, false, false));
        exact("ROLE", "ROLE_ENGINEER", READ_KEY, grant(true, false, false));
        roles.set(List.of("ROLE_ENGINEER"));

        C5PermissionDecision decision = service.decide("alice", READ_KEY, C5Operation.READ);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.operation()).isEqualTo(C5Operation.READ);
        assertThat(decision.sources()).containsExactly("прямой", "роль: ROLE_ENGINEER");
    }

    @Test
    void subjectDecisionListsEverySourceInsteadOfTheFirstSufficientOne() {
        exact("USER", "alice", OPERATION_KEY, grant(false, true, false));
        exact("ROLE", "ROLE_ENGINEER", OPERATION_KEY, grant(false, true, false));
        roles.set(List.of("ROLE_ENGINEER"));

        C5PermissionDecision decision = service.decideForSubject(AccessGrant.SubjectType.USER,
            "alice", OPERATION_KEY, C5Operation.UPDATE);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.sources()).containsExactly("прямой", "роль: ROLE_ENGINEER");
        // Enforcement замыкается на первом достаточном источнике; полный список нужен тому, кто
        // показывает и объясняет решение, — решение при этом одно и то же.
        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.UPDATE).sources())
            .containsExactly("прямой");
    }

    @Test
    void subjectDecisionForARoleUsesExactlyThatRolesOwnGrant() {
        exact("ROLE", "ROLE_ENGINEER", OPERATION_KEY, grant(false, true, false));
        roles.set(List.of());

        C5PermissionDecision decision = service.decideForSubject(AccessGrant.SubjectType.ROLE,
            "ROLE_ENGINEER", OPERATION_KEY, C5Operation.UPDATE);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.sources()).containsExactly("роль: ROLE_ENGINEER");
        assertThat(service.decideForSubject(AccessGrant.SubjectType.ROLE, "ROLE_OTHER",
            OPERATION_KEY, C5Operation.UPDATE).allowed()).isFalse();
        // Роль без назначения никого не авторизует через пользователя — решение пользователю
        // по-прежнему не собирается из чужих ролей.
        assertThat(service.decideForSubject(AccessGrant.SubjectType.USER, "alice",
            OPERATION_KEY, C5Operation.UPDATE).allowed()).isFalse();
    }

    @Test
    void subjectDecisionRefusesAnOperationTheDefinitionDoesNotDeclare() {
        assertThatThrownBy(() -> service.decideForSubject(AccessGrant.SubjectType.USER, "alice",
            "ACTION:RegisterWrite", C5Operation.UPDATE))
            .isInstanceOf(C5PermissionConfigurationException.class)
            .hasMessageContaining("cannot decide UPDATE");
    }

    @Test
    void subjectReadDecisionKeepsTheReadSpellingAndItsSources() {
        exact("USER", "alice", READ_KEY, grant(true, false, false));
        exact("ROLE", "ROLE_ENGINEER", READ_KEY, grant(true, false, false));
        roles.set(List.of("ROLE_ENGINEER"));

        assertThat(service.decideForSubject(AccessGrant.SubjectType.USER, "alice", READ_KEY,
            C5Operation.READ).sources()).containsExactly("прямой", "роль: ROLE_ENGINEER");
        assertThat(service.decideForSubject(AccessGrant.SubjectType.ROLE, "ROLE_ENGINEER",
            READ_KEY, C5Operation.READ).sources()).containsExactly("роль: ROLE_ENGINEER");
        // Legacy ATTR-ключ по-прежнему разрешается в ключ чтения владеющей сущности.
        assertThat(service.decideForSubject(AccessGrant.SubjectType.USER, "alice",
            C5PermissionKeys.attributeRead(String.class, "code"), C5Operation.READ).allowed())
            .isTrue();
    }

    @Test
    void subjectDecisionDeniesABlankSubjectBeforeAnyGrantIsRead() {
        assertThat(service.decideForSubject(AccessGrant.SubjectType.USER, "  ", OPERATION_KEY,
            C5Operation.UPDATE).reason()).isEqualTo(C5DecisionReason.UNAUTHENTICATED);
        assertThat(service.decideForSubject(AccessGrant.SubjectType.USER, "  ", READ_KEY,
            C5Operation.READ).reason()).isEqualTo(C5DecisionReason.UNAUTHENTICATED);
    }

    @Test
    void denyCarriesNoSourcesAndASafeMessage() {
        C5PermissionDecision decision = service.decide("alice", OPERATION_KEY, C5Operation.DELETE);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.sources()).isEmpty();
        assertThat(decision.message()).isEqualTo("Нет прав на операцию с объектом");
        assertThat(decision.message()).doesNotContain(OPERATION_KEY);
    }

    @Test
    void namedOperationDenialUsesTheActionReason() {
        C5PermissionDecision decision = service.decideAction("alice", "RegisterWrite");

        assertThat(decision.reason()).isEqualTo(C5DecisionReason.ACTION_PERMISSION_DENIED);
        assertThat(decision.key()).isEqualTo("ACTION:RegisterWrite");
    }

    @Test
    void unauthenticatedSubjectIsDeniedBeforeAnyGrantRead() {
        assertThat(service.decide(null, OPERATION_KEY, C5Operation.UPDATE).reason())
            .isEqualTo(C5DecisionReason.UNAUTHENTICATED);
        assertThat(service.decide("  ", OPERATION_KEY, C5Operation.UPDATE).reason())
            .isEqualTo(C5DecisionReason.UNAUTHENTICATED);
        assertThat(service.decide(null, READ_KEY, C5Operation.READ).reason())
            .isEqualTo(C5DecisionReason.UNAUTHENTICATED);
    }

    @Test
    void unknownDefinitionIsAConfigurationErrorEvenForAnUnprivilegedSubject() {
        assertThatThrownBy(() -> service.decide("alice", "ENTITY:NeverDeclared", C5Operation.UPDATE))
            .isInstanceOf(C5PermissionConfigurationException.class)
            .hasMessageContaining("Unknown C5 permission definition");
    }

    @Test
    void operationTheDefinitionDoesNotDeclareIsAConfigurationError() {
        assertThatThrownBy(() -> service.decide("alice", "ACTION:RegisterWrite", C5Operation.UPDATE))
            .isInstanceOf(C5PermissionConfigurationException.class)
            .hasMessageContaining("cannot decide UPDATE");
    }

    @Test
    void storageFailurePropagatesInsteadOfTurningIntoAnAllow() {
        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            any(), any(), any())).thenThrow(new IllegalStateException("grant store unavailable"));

        assertThatThrownBy(() -> service.decide("alice", OPERATION_KEY, C5Operation.UPDATE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("grant store unavailable");
    }

    @Test
    void entityDecisionChoosesTheReadAndOperationKeySpellings() {
        exact("USER", "alice", READ_KEY, grant(true, false, false));
        exact("USER", "alice", OPERATION_KEY, grant(false, true, false));

        assertThat(service.decideEntity("alice", String.class, C5Operation.READ).key()).isEqualTo(READ_KEY);
        assertThat(service.decideEntity("alice", String.class, C5Operation.UPDATE).key())
            .isEqualTo(OPERATION_KEY);
        assertThatThrownBy(() -> service.decideEntity("alice", String.class, C5Operation.EXECUTE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("named operation");
    }

    @Test
    void requireThrowsAnAccessDeniedExceptionForADeniedOperation() {
        assertThatThrownBy(() -> service.requireEntity("alice", String.class, C5Operation.CREATE))
            .isInstanceOf(C5AccessDeniedException.class)
            .hasMessageContaining(OPERATION_KEY);
        assertThatThrownBy(() -> service.requireAction("alice", "RegisterWrite"))
            .isInstanceOf(C5AccessDeniedException.class);
    }

    @Test
    void requirePassesForAPermittedOperation() {
        exact("USER", "alice", OPERATION_KEY, grant(false, true, false));

        service.requireEntity("alice", String.class, C5Operation.UPDATE);
        service.require("alice", OPERATION_KEY, C5Operation.CREATE);
    }

    @Test
    void deniedDecisionCannotCarrySourcesOrBeMarkedPermitted() {
        assertThatThrownBy(() -> new C5PermissionDecision(false, C5DecisionReason.PERMITTED,
            OPERATION_KEY, C5Operation.UPDATE, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> C5PermissionDecision.denied(C5DecisionReason.PERMITTED,
            OPERATION_KEY, C5Operation.UPDATE))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
