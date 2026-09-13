package org.ip.security;

import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.repository.JournalRepository;
import org.ip.security.probe.RlsDenyProbeRepository;
import org.ip.security.probe.RlsDenyProbeSectionRowRepository;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsAccessDeniedException;
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
    @Autowired RlsDenyProbeRepository probe;
    @Autowired RlsDenyProbeSectionRowRepository sectionRows;
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

    /**
     * Запреты границы до {@code proceed()}: канал, который не может доказать построчное
     * право чтения/записи, закрыт даже у субъекта с полным грантом. Поэтому каждый тест
     * аутентифицирует пользователя И даёт ему право — отказ обязан прийти от канала, а не
     * от отсутствия прав.
     */
    @Test
    void nativeQueryIsDeniedForProtectedEntity() {
        authenticateWithFullGrant("deny-native");

        assertThatThrownBy(() -> probe.findByCodeNative("C2-deny-native"))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("Native query");
    }

    @Test
    void bulkModifyingQueryIsDeniedForProtectedEntity() {
        authenticateWithFullGrant("deny-bulk");

        assertThatThrownBy(() -> probe.deleteByCodeBulk("C2-deny-bulk"))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("Bulk mutation");
    }

    @Test
    void deleteAllInBatchIsDeniedForProtectedEntity() {
        authenticateWithFullGrant("deny-batch");

        assertThatThrownBy(() -> journals.deleteAllInBatch())
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("Bulk mutation");
    }

    @Test
    void flushIsDeniedForProtectedRepository() {
        authenticateWithFullGrant("deny-flush");

        assertThatThrownBy(() -> journals.flush())
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("flush");
    }

    @Test
    void deleteByIdIsDeniedBecauseIdOnlyMutationCannotBeAuthorized() {
        authenticateWithFullGrant("deny-id-only");

        assertThatThrownBy(() -> journals.deleteById(1L))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("requires entity values");
    }

    /**
     * Строка owned-секции не объявляет собственной RLS-политики: доступ наследуется от
     * aggregate root, поэтому произвольный derived/custom запрос к строкам не может
     * выразить обязательный предикат владельца. Единственная поддержанная граница —
     * aggregate section service.
     */
    @Test
    void ownedSectionRowRepositoryIsDenied() {
        authenticateWithFullGrant("deny-section-row");

        assertThatThrownBy(() -> sectionRows.findAll())
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("use the aggregate section service");
    }

    /**
     * Полный грант на измерение JOURNAL: запреты выше проверяются на субъекте, который
     * вправе читать и изменять журналы, — то есть отказ не маскирует отсутствие прав.
     */
    private void authenticateWithFullGrant(String username) {
        login(username);
        grants.save(grant(username, null, true, true));
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
