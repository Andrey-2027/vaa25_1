package org.ip.security;

import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.repository.JournalRepository;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RlsRepositoryEnforcementIT {

    @MockitoBean DataInitializer dataInitializer;
    @Autowired JournalRepository journals;
    @Autowired AccessGrantRepository grants;
    @Autowired TransactionTemplate transactions;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void directRepositoryReadUsesTheRepositoryTransactionSession() {
        Seed seed = seed("repository-read");

        assertThat(journals.findAll()).extracting(Journal::getId)
            .containsExactly(seed.allowed().getId());
    }

    @Test
    void commitTimeDirtyCheckingRequiresExplicitWriteAuthorization() {
        Seed seed = seed("dirty-check");

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
            journals.findById(seed.allowed().getId()).orElseThrow().setName("forbidden")))
            .isInstanceOf(org.springframework.transaction.TransactionSystemException.class)
            .hasRootCauseInstanceOf(org.ipro.rls.RlsAccessDeniedException.class);

        assertThat(journals.findById(seed.allowed().getId()).orElseThrow().getName())
            .isEqualTo("C2-dirty-check-A");
    }

    private Seed seed(String username) {
        login(username);
        AccessGrant bootstrap = grant(username, null, true, true);
        Journal allowed = journals.save(journal("C2-" + username + "-A"));
        Journal denied = journals.save(journal("C2-" + username + "-B"));
        grants.delete(bootstrap);
        grants.save(grant(username, allowed.getId(), true, false));
        return new Seed(allowed, denied);
    }

    private AccessGrant grant(String username, Long id, boolean read, boolean update) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(username);
        grant.setDimension("JOURNAL");
        grant.setDimensionValueId(id);
        grant.setCanRead(read);
        grant.setCanUpdate(update);
        grants.save(grant);
        return grant;
    }

    private void login(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private static Journal journal(String code) {
        Journal journal = new Journal();
        journal.setCode(code);
        journal.setName(code);
        return journal;
    }

    private record Seed(Journal allowed, Journal denied) {
    }
}
