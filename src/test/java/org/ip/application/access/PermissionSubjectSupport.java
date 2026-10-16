package org.ip.application.access;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.ip.model.User;
import org.ip.model.Role;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;

/** Common persisted subject fixture, without tests. */
abstract class PermissionSubjectSupport extends PermissionIntegrationSupport {
    @Autowired PermissionStateService states;
    @Autowired AccessGrantRepository repository;
    @Autowired AccessService rls;
    @Autowired C5PermissionDecisionService c5;
    @Autowired SecurityContextHolderStrategy strategy;
    @PersistenceContext EntityManager em;
    User user;
    Role first;
    Role second;
    @BeforeEach void setupSubject() {
        authenticate("ROLE_ADMIN");
        first = new Role(); first.setName("P2 first"); em.persist(first);
        second = new Role(); second.setName("P2 second"); em.persist(second);
        user = new User("p2-user", "fixture-password");
        user.setRoles(new HashSet<>(Set.of(first, second))); em.persist(user); em.flush();
    }
    @AfterEach void clearAuthentication() { strategy.clearContext(); SecurityContextHolder.clearContext(); }
    protected void authenticate(String authority) {
        var context = strategy.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("p2-admin", "fixture",
            List.of(new SimpleGrantedAuthority(authority))));
        strategy.setContext(context); SecurityContextHolder.setContext(context);
    }
}
