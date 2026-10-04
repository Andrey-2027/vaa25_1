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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = DefaultC5PermissionEvaluatorJpaTest.JpaConfiguration.class)
class DefaultC5PermissionEvaluatorJpaTest {

    @Autowired
    private AccessGrantRepository grantRepository;

    private final AtomicReference<List<String>> roles = new AtomicReference<>(List.of());
    private C5PermissionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        grantRepository.deleteAll();
        RlsRoleResolver roleResolver = username -> roles.get();
        evaluator = new DefaultC5PermissionEvaluator(grantRepository, roleResolver);
    }

    @Test
    void directGrantCanBeRevokedAndNoGrantFailsClosed() {
        String key = C5PermissionKeys.entityRead(String.class);
        assertThat(evaluator.isEntityReadPermitted("alice", String.class)).isFalse();

        AccessGrant grant = grant(AccessGrant.SubjectType.USER, "alice", key, null);
        grantRepository.saveAndFlush(grant);
        assertThat(evaluator.isEntityReadPermitted("alice", String.class)).isTrue();

        grantRepository.delete(grant);
        grantRepository.flush();
        assertThat(evaluator.isEntityReadPermitted("alice", String.class)).isFalse();
    }

    @Test
    void roleGrantIsEffectiveOnlyWhileRoleIsAssigned() {
        String key = C5PermissionKeys.entityRead(String.class);
        grantRepository.saveAndFlush(grant(AccessGrant.SubjectType.ROLE, "ROLE_ENGINEER", key, null));
        roles.set(List.of("ROLE_ENGINEER"));

        assertThat(evaluator.isEntityReadPermitted("alice", String.class)).isTrue();

        roles.set(List.of());
        assertThat(evaluator.isEntityReadPermitted("alice", String.class)).isFalse();
    }

    @Test
    void valueScopedRlsGrantDoesNotBecomeGlobalC5Permission() {
        String key = C5PermissionKeys.entityRead(String.class);
        grantRepository.saveAndFlush(grant(AccessGrant.SubjectType.USER, "alice", key, 42L));

        assertThat(evaluator.isEntityReadPermitted("alice", String.class)).isFalse();
    }

    private AccessGrant grant(AccessGrant.SubjectType type, String subject, String dimension,
                              Long dimensionValueId) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(type);
        grant.setSubjectKey(subject);
        grant.setDimension(dimension);
        grant.setDimensionValueId(dimensionValueId);
        grant.setCanRead(true);
        return grant;
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = AccessGrant.class)
    @EnableJpaRepositories(basePackageClasses = AccessGrantRepository.class)
    static class JpaConfiguration { }
}
