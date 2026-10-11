package org.ipro.rls.c5;

import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsRoleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0017 §3 на настоящем repository: одна строка гранта — одно независимое решение об операции,
 * a {@code '*'} и value-scoped строки операцию не авторизуют.
 */
@DataJpaTest
@ContextConfiguration(classes = C5PermissionDecisionServiceJpaTest.JpaConfiguration.class)
class C5PermissionDecisionServiceJpaTest {

    private static final String OPERATION_KEY = C5PermissionKeys.entityOperations(String.class);
    private static final String ACTION_KEY = C5PermissionKeys.action("RegisterWrite");

    @Autowired
    private AccessGrantRepository grantRepository;

    private final AtomicReference<List<String>> roles = new AtomicReference<>(List.of());
    private C5PermissionDecisionService service;

    @BeforeEach
    void setUp() {
        grantRepository.deleteAll();
        grantRepository.flush();
        RlsRoleResolver roleResolver = username -> roles.get();
        C5OperationPermissionRegistry operationRegistry = new C5OperationPermissionRegistry();
        operationRegistry.register(C5PermissionDefinition.entityOperations(String.class,
            Set.of(C5Operation.CREATE, C5Operation.UPDATE, C5Operation.DELETE)));
        operationRegistry.register(C5PermissionDefinition.action("RegisterWrite"));
        service = new C5PermissionDecisionService(grantRepository, roleResolver, operationRegistry,
            new C5ReadDecisionService(grantRepository, roleResolver));
    }

    @Test
    void updateAndDeleteRowsDecideIndependentlyAndRevokeFailsClosed() {
        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.UPDATE).allowed()).isFalse();

        AccessGrant update = grant(AccessGrant.SubjectType.USER, "alice", OPERATION_KEY, null,
            false, true, false);
        grantRepository.saveAndFlush(update);

        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.CREATE).allowed()).isTrue();
        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.UPDATE).allowed()).isTrue();
        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.DELETE).allowed()).isFalse();
        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.CREATE).sources())
            .containsExactly("прямой");

        AccessGrant delete = grant(AccessGrant.SubjectType.USER, "alice", OPERATION_KEY, null,
            false, false, true);
        grantRepository.saveAndFlush(delete);
        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.DELETE).allowed()).isTrue();

        grantRepository.delete(update);
        grantRepository.delete(delete);
        grantRepository.flush();

        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.CREATE).allowed()).isFalse();
        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.DELETE).allowed()).isFalse();
    }

    @Test
    void wildcardAndValueScopedRowsDoNotAuthorizeAnOperation() {
        grantRepository.saveAndFlush(grant(AccessGrant.SubjectType.USER, "alice", "*", null,
            false, true, true));
        grantRepository.saveAndFlush(grant(AccessGrant.SubjectType.USER, "alice", OPERATION_KEY, 42L,
            false, true, true));

        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.UPDATE).allowed()).isFalse();
        assertThat(service.decide("alice", OPERATION_KEY, C5Operation.DELETE).allowed()).isFalse();
    }

    @Test
    void subjectDecisionIgnoresWildcardAndValueScopedRowsOnRealStorage() {
        grantRepository.saveAndFlush(grant(AccessGrant.SubjectType.USER, "alice", "*", null,
            true, true, true));
        grantRepository.saveAndFlush(grant(AccessGrant.SubjectType.USER, "alice", OPERATION_KEY, 42L,
            true, true, true));

        C5PermissionDecision decision = service.decideForSubject(AccessGrant.SubjectType.USER,
            "alice", OPERATION_KEY, C5Operation.UPDATE);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.sources()).isEmpty();
        assertThat(decision.reason()).isEqualTo(C5DecisionReason.ENTITY_PERMISSION_DENIED);
    }

    @Test
    void roleGrantIsEffectiveOnlyWhileRoleIsAssigned() {
        grantRepository.saveAndFlush(grant(AccessGrant.SubjectType.ROLE, "ROLE_ENGINEER",
            ACTION_KEY, null, false, true, false));
        roles.set(List.of("ROLE_ENGINEER"));

        assertThat(service.decideAction("alice", "RegisterWrite").sources())
            .containsExactly("роль: ROLE_ENGINEER");

        roles.set(List.of());
        assertThat(service.decideAction("alice", "RegisterWrite").allowed()).isFalse();
        assertThat(service.decideAction("alice", "RegisterWrite").reason())
            .isEqualTo(C5DecisionReason.ACTION_PERMISSION_DENIED);
    }

    @Test
    void readRowDoesNotDecideAnOperationAndUpdateRowDoesNotDecideRead() {
        grantRepository.saveAndFlush(grant(AccessGrant.SubjectType.USER, "alice", OPERATION_KEY,
            null, true, false, false));

        assertThat(service.decideEntity("alice", String.class, C5Operation.READ).allowed()).isFalse();
        assertThat(service.decideEntity("alice", String.class, C5Operation.UPDATE).allowed()).isFalse();
    }

    private AccessGrant grant(AccessGrant.SubjectType type, String subject, String dimension,
                              Long dimensionValueId, boolean read, boolean update, boolean delete) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(type);
        grant.setSubjectKey(subject);
        grant.setDimension(dimension);
        grant.setDimensionValueId(dimensionValueId);
        grant.setCanRead(read);
        grant.setCanUpdate(update);
        grant.setCanDelete(delete);
        return grant;
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = AccessGrant.class)
    @EnableJpaRepositories(basePackageClasses = AccessGrantRepository.class)
    static class JpaConfiguration { }
}
