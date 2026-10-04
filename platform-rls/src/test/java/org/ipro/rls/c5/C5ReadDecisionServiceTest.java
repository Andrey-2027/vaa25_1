package org.ipro.rls.c5;

import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsRoleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** F-REST-READ-3, З2: одна exact/global/read семантика для сервера и админ-экрана. */
class C5ReadDecisionServiceTest {

    private static final String KEY = "ENTITY:org.example.Model";

    private AccessGrantRepository grantRepository;
    private AtomicReference<List<String>> roles;
    private C5ReadDecisionService service;

    @BeforeEach
    void setUp() {
        grantRepository = Mockito.mock(AccessGrantRepository.class);
        roles = new AtomicReference<>(List.of());
        RlsRoleResolver roleResolver = username -> roles.get();
        service = new C5ReadDecisionService(grantRepository, roleResolver);
    }

    private void exact(String subjectType, String subjectKey, AccessGrant... grants) {
        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.valueOf(subjectType), subjectKey, KEY))
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
    void exactGlobalUserReadGrantAllowsRead() {
        exact("USER", "alice", grant(true, false, false));

        assertThat(service.isReadGranted(KEY, "alice")).isTrue();
        C5ReadDecisionService.C5ReadDecision decision =
            service.decide(KEY, AccessGrant.SubjectType.USER, "alice");
        assertThat(decision.granted()).isTrue();
        assertThat(decision.direct()).isTrue();
        assertThat(decision.roles()).isEmpty();
    }

    @Test
    void assignedRoleGrantAllowsUserAndRoleSubject() {
        exact("ROLE", "ROLE_ENGINEER", grant(true, false, false));
        roles.set(List.of("ROLE_ENGINEER"));

        assertThat(service.isReadGranted(KEY, "alice")).isTrue();
        C5ReadDecisionService.C5ReadDecision userDecision =
            service.decide(KEY, AccessGrant.SubjectType.USER, "alice");
        assertThat(userDecision.granted()).isTrue();
        assertThat(userDecision.direct()).isFalse();
        assertThat(userDecision.roles()).containsExactly("ROLE_ENGINEER");

        C5ReadDecisionService.C5ReadDecision roleDecision =
            service.decide(KEY, AccessGrant.SubjectType.ROLE, "ROLE_ENGINEER");
        assertThat(roleDecision.granted()).isTrue();
        assertThat(roleDecision.direct()).isTrue();
    }

    @Test
    void roleWithoutAssignmentDoesNotAuthorizeUser() {
        exact("ROLE", "ROLE_ENGINEER", grant(true, false, false));
        roles.set(List.of());

        assertThat(service.isReadGranted(KEY, "alice")).isFalse();
        assertThat(service.decide(KEY, AccessGrant.SubjectType.USER, "alice").granted()).isFalse();
    }

    @Test
    void noGrantAndRevokeFailClosed() {
        exact("USER", "alice");

        assertThat(service.isReadGranted(KEY, "alice")).isFalse();
        assertThat(service.decide(KEY, AccessGrant.SubjectType.USER, "alice").granted()).isFalse();
    }

    @Test
    void valueScopedRlsRowDoesNotBecomeGlobalC5Read() {
        AccessGrant valueScoped = grant(true, false, false);
        valueScoped.setDimensionValueId(42L);
        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimension(
            AccessGrant.SubjectType.USER, "alice", KEY)).thenReturn(List.of(valueScoped));
        exact("USER", "alice");

        assertThat(service.isReadGranted(KEY, "alice")).isFalse();
        assertThat(service.decide(KEY, AccessGrant.SubjectType.USER, "alice").granted()).isFalse();
    }

    @Test
    void wildcardGrantDoesNotAuthorizeC5Read() {
        AccessGrant wildcard = grant(true, false, false);
        wildcard.setDimension("*");
        when(grantRepository.findUserGrants(KEY, "alice")).thenReturn(List.of(wildcard));
        exact("USER", "alice");

        assertThat(service.isReadGranted(KEY, "alice")).isFalse();
        assertThat(service.decide(KEY, AccessGrant.SubjectType.USER, "alice").granted()).isFalse();
    }

    @Test
    void updateOrDeleteFlagsAloneDoNotGrantC5Read() {
        exact("USER", "alice", grant(false, true, true));

        assertThat(service.isReadGranted(KEY, "alice")).isFalse();
    }

    @Test
    void storageFailurePropagatesInsteadOfAllowOrEmptySuccess() {
        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            any(), any(), any())).thenThrow(new IllegalStateException("grant store unavailable"));

        assertThatThrownBy(() -> service.isReadGranted(KEY, "alice"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("grant store unavailable");
        assertThatThrownBy(() -> service.decide(KEY, AccessGrant.SubjectType.USER, "alice"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void directAndRoleSourcesAreBothReported() {
        exact("USER", "alice", grant(true, false, false));
        exact("ROLE", "ROLE_ENGINEER", grant(true, false, false));
        roles.set(List.of("ROLE_ENGINEER", "ROLE_OTHER"));

        C5ReadDecisionService.C5ReadDecision decision =
            service.decide(KEY, AccessGrant.SubjectType.USER, "alice");
        assertThat(decision.granted()).isTrue();
        assertThat(decision.direct()).isTrue();
        assertThat(decision.roles()).containsExactly("ROLE_ENGINEER");
    }
}
