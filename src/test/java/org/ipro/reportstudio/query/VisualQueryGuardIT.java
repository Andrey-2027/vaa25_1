package org.ipro.reportstudio.query;

import org.ip.Application;
import org.ip.model.PrdSpec;
import org.ip.repository.UserRepository;
import org.ip.security.UserRepositoryRlsRoleResolver;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.ipro.rls.config.RlsPersistenceAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.test.context.ContextConfiguration;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
// org.ip объявлен в Application#@EnableJpaRepositories (иначе дублирование бобов репозиториев в срезе)
@ImportAutoConfiguration(RlsPersistenceAutoConfiguration.class)
@ContextConfiguration(classes = Application.class)
class VisualQueryGuardIT {
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @Autowired private AccessGrantRepository accessGrantRepository;
    @Autowired private UserRepository userRepository;

    private ReportQueryGuard guard;

    @BeforeEach
    void setUp() {
        var registry = new RlsDimensionRegistry("org.ip");
        registry.rebuild();
        var access = new AccessService(accessGrantRepository,
                new UserRepositoryRlsRoleResolver(userRepository), registry);
        var grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey("admin");
        grant.setDimension("*");
        grant.setCanRead(true);
        grant.setCanUpdate(false);
        grant.setCanDelete(false);
        accessGrantRepository.saveAndFlush(grant);
        RlsCurrentUser user = () -> "admin";
        guard = new ReportQueryGuard(new SqmQuerySemanticAnalyzer(entityManagerFactory),
                access, registry, user, entityManagerFactory);
    }

    @Test
    void generatedQueryPassesRealHibernateGuard() {
        var generated = VisualQueryCompiler.compile(new VisualQueryDefinition(
                entityNameFor(PrdSpec.class), "s", java.util.List.of(
                new VisualQueryDefinition.SelectField("codeSpec", "codeSpec"))));

        GuardResult result = guard.guard(generated.jpql(), Set.of());

        assertThat(result.allowed()).as(result.errors().toString()).isTrue();
        assertThat(result.selectFields()).hasSize(1);
        assertThat(result.selectFields().get(0).name()).isEqualTo("codeSpec");
    }

    @Test
    void realGuardRejectsGeneratedInvalidEntity() {
        var generated = VisualQueryCompiler.compile(new VisualQueryDefinition(
                "DefinitelyMissingEntity", "x", java.util.List.of(
                new VisualQueryDefinition.SelectField("code", "code"))));

        GuardResult result = guard.guard(generated.jpql(), Set.of());

        assertThat(result.allowed()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    private static String entityNameFor(Class<?> type) {
        return type.getSimpleName();
    }
}
