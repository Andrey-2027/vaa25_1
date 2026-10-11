package com.example.materialrequests.acceptance;

import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.ipro.data.EntityDataAccess;
import org.ipro.form.MetadataDrivenItemFormSaveAdapter;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsRoleResolver;
import org.ipro.rls.RlsAccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "consumer.demo-password=bootstrap-test-password")
@ActiveProfiles(resolver = ConsumerTestProfiles.class)
class BootstrapTest {

    @Autowired ApplicationContext context;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired RlsCurrentUser currentUser;
    @Autowired RlsRoleResolver roleResolver;
    @Autowired Environment environment;
    @Autowired javax.sql.DataSource dataSource;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void starterProvidesPersistenceCanonicalAccessAndFormSaveAdapter() throws Exception {
        if ("postgres".equals(System.getProperty("consumer.test.database"))) {
            try (var connection = dataSource.getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            }
        }
        assertThat(context.getBean(EntityDataAccess.class)).isNotNull();
        assertThat(context.getBean(MetadataDrivenItemFormSaveAdapter.class)).isNotNull();
        assertThat(entityManagerFactory.getMetamodel().entity(AccessGrant.class)).isNotNull();
        assertThat(environment.getProperty("rls.write-boundary.enforced", Boolean.class)).isTrue();
        assertThat(environment.getProperty("ipro.telemetry.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("ipro.telemetry.db-journal", Boolean.class)).isTrue();
    }

    @Test
    void missingAndAnonymousAuthenticationCannotAcquireSystemAuthority() {
        assertThat(currentUser.username()).isNull();
        assertThatThrownBy(currentUser::requireAuthenticatedUsername).isInstanceOf(RlsAccessDeniedException.class);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "consumer", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThat(currentUser.username()).isNull();
        assertThatThrownBy(currentUser::requireAuthenticatedUsername).isInstanceOf(RlsAccessDeniedException.class);
    }

    @Test
    void subjectAndRoleAdaptersUseTheConsumerIdentitySource() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", null,
                        List.of(new SimpleGrantedAuthority("ROLE_EDITOR"))));
        assertThat(currentUser.requireAuthenticatedUsername()).isEqualTo("alice");
        assertThat(roleResolver.rolesOf("alice")).containsExactly("EDITOR");
        assertThat(roleResolver.rolesOf("bob")).containsExactly("READER");
        assertThat(roleResolver.rolesOf("unknown")).isEmpty();
        assertThat(roleResolver.rolesOf(null)).isEmpty();
    }
}
