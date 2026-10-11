package org.ipro.rls.c5;

import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsRoleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class DefaultC5PermissionEvaluatorTest {

    static class FirstPackage { static class Model { } }
    static class SecondPackage { static class Model { } }

    private AccessGrantRepository grantRepository;
    private RlsRoleResolver roleResolver;
    private DefaultC5PermissionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        grantRepository = Mockito.mock(AccessGrantRepository.class);
        roleResolver = Mockito.mock(RlsRoleResolver.class);
        evaluator = new DefaultC5PermissionEvaluator(grantRepository, roleResolver);
    }

    @Test
    void isApiPermitted_whenDirectUserGrantExists_returnsTrue() {
        String key = "REST:specifications:v1:LIST";
        AccessGrant grant = new AccessGrant();
        grant.setCanRead(true);

        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, "john", key))
            .thenReturn(List.of(grant));

        assertThat(evaluator.isApiPermitted("john", "specifications", 1, "LIST")).isTrue();
    }

    @Test
    void isApiPermitted_whenRoleGrantExists_returnsTrue() {
        String key = "REST:specifications:v1:LIST";
        AccessGrant grant = new AccessGrant();
        grant.setCanRead(true);

        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, "john", key))
            .thenReturn(List.of());
        when(roleResolver.rolesOf("john")).thenReturn(List.of("ROLE_ENGINEER"));
        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.ROLE, "ROLE_ENGINEER", key))
            .thenReturn(List.of(grant));

        assertThat(evaluator.isApiPermitted("john", "specifications", 1, "LIST")).isTrue();
    }

    @Test
    void isApiPermitted_whenNoGrant_returnsFalse() {
        String key = "REST:specifications:v1:LIST";

        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, "john", key))
            .thenReturn(List.of());
        when(roleResolver.rolesOf("john")).thenReturn(List.of("ROLE_ENGINEER"));
        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.ROLE, "ROLE_ENGINEER", key))
            .thenReturn(List.of());

        assertThat(evaluator.isApiPermitted("john", "specifications", 1, "LIST")).isFalse();
    }

    @Test
    void isApiPermitted_requiresExactKeyAndMajorIsolation() {
        // v1 does not grant v2
        String keyV1 = "REST:specifications:v1:LIST";
        String keyV2 = "REST:specifications:v2:LIST";
        AccessGrant grantV1 = new AccessGrant();
        grantV1.setCanRead(true);

        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, "john", keyV1))
            .thenReturn(List.of(grantV1));
        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, "john", keyV2))
            .thenReturn(List.of());
        when(roleResolver.rolesOf("john")).thenReturn(List.of());

        assertThat(evaluator.isApiPermitted("john", "specifications", 1, "LIST")).isTrue();
        assertThat(evaluator.isApiPermitted("john", "specifications", 2, "LIST")).isFalse();
    }

    @Test
    void isEntityReadPermitted_checksExactEntityTypeKey() {
        String key = "ENTITY:java.lang.String";
        AccessGrant grant = new AccessGrant();
        grant.setCanRead(true);

        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, "john", key))
            .thenReturn(List.of(grant));

        assertThat(evaluator.isEntityReadPermitted("john", String.class)).isTrue();
        assertThat(evaluator.isEntityReadPermitted("john", Integer.class)).isFalse();
    }

    @Test
    void legacyAttributeMethodUsesEntityReadForEveryField() {
        String key = C5PermissionKeys.entityRead(String.class);
        AccessGrant grant = new AccessGrant();
        grant.setCanRead(true);

        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, "john", key))
            .thenReturn(List.of(grant));

        assertThat(evaluator.isAttributeReadPermitted("john", String.class, "length")).isTrue();
        assertThat(evaluator.isAttributeReadPermitted("john", String.class, "bytes")).isTrue();
    }

    @Test
    void simpleNameGrantDoesNotAuthorizeCollidingEntityTypes() {
        AccessGrant legacySimpleNameGrant = new AccessGrant();
        legacySimpleNameGrant.setCanRead(true);
        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, "john", "ENTITY:Model"))
            .thenReturn(List.of(legacySimpleNameGrant));

        assertThat(evaluator.isEntityReadPermitted("john", FirstPackage.Model.class)).isFalse();
        assertThat(evaluator.isEntityReadPermitted("john", SecondPackage.Model.class)).isFalse();
    }

    @Test
    void fullyQualifiedEntityGrantIsIsolatedToItsType() {
        AccessGrant grant = new AccessGrant();
        grant.setCanRead(true);
        String firstKey = "ENTITY:" + FirstPackage.Model.class.getName();
        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, "john", firstKey)).thenReturn(List.of(grant));

        assertThat(evaluator.isEntityReadPermitted("john", FirstPackage.Model.class)).isTrue();
        assertThat(evaluator.isEntityReadPermitted("john", SecondPackage.Model.class)).isFalse();
    }

    @Test
    void simpleNameAttributeGrantDoesNotAuthorizeCollidingEntityTypes() {
        AccessGrant legacySimpleNameGrant = new AccessGrant();
        legacySimpleNameGrant.setCanRead(true);
        when(grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, "john", "ATTR:Model:id"))
            .thenReturn(List.of(legacySimpleNameGrant));

        assertThat(evaluator.isAttributeReadPermitted("john", FirstPackage.Model.class, "id")).isFalse();
        assertThat(evaluator.isAttributeReadPermitted("john", SecondPackage.Model.class, "id")).isFalse();
    }
}
