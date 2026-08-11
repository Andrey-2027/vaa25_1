package org.ip.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.ip.config.DataInitializer;
import org.ip.service.ReceivingDocumentService;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsContext;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsStatementGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RLS-канарейка "тихих утечек" (Фаза 6): композиция в SqlStatementInspector и
 * фиксирует SELECT по таблицам RLS-сущностей без включённого Hibernate-фильтра.
 *
 * Сервисный путь (ensureRlsEnabled) и осознанный bypass (withRlsDisabled,
 * runAsSystem) — тишина; сырой Criteria/JPQL в обход репозиториев — нарушение.
 * В тестовой конфигурации {@code rls.guard.strict=true} (коллектор нарушений).
 */
@SpringBootTest
class RlsStatementGuardTest {

    @MockitoBean
    private DataInitializer dataInitializer;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private ReceivingDocumentService documentService;

    @Autowired
    private RlsFilterActivator rlsFilterActivator;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @BeforeEach
    void setUp() {
        RlsStatementGuard.reset();
    }

    private void grantWildcardToAdmin() {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey("admin");
        grant.setDimension("*");
        grant.setDimensionValueId(null);
        grant.setCanRead(true);
        grant.setCanUpdate(true);
        grant.setCanDelete(true);
        accessGrantRepository.save(grant);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin", "n/a", List.of()));
    }

    /** Сырой Criteria-запрос по RLS-таблице без ensureRlsEnabled — фиксируется нарушение. */
    @Test
    void rawCriteriaSelectWithoutEnsureRlsEnabledIsReported() {
        entityManager.createQuery("select d from ReceivingDocument d").getResultList();

        List<String> violations = RlsStatementGuard.violations();
        assertThat(violations).isNotEmpty();
        assertThat(String.join("\n", violations)).contains("receiving_document");
        assertThat(RlsStatementGuard.violationCount()).isGreaterThan(0);
    }

    /** Сервисный путь: ensureRlsEnabled включил фильтры — канарейка молчит (в т.ч. wildcard-пропуски). */
    @Test
    void serviceReadWithEnabledFiltersIsSilent() {
        grantWildcardToAdmin();

        documentService.findAll();

        assertThat(RlsStatementGuard.violations()).isEmpty();
        assertThat(RlsStatementGuard.violationCount()).isZero();
    }

    /** Сознательное выключение фильтров (withRlsDisabled — ReferenceCheckService) — не нарушение. */
    @Test
    void withRlsDisabledWindowIsSilent() {
        rlsFilterActivator.withRlsDisabled(entityManager, () -> {
            entityManager.createQuery("select d from ReceivingDocument d").getResultList();
            return null;
        });

        assertThat(RlsStatementGuard.violations()).isEmpty();
        assertThat(RlsStatementGuard.violationCount()).isZero();
    }

    /** Фоновая задача (RlsContext.runAsSystem) — не нарушение. */
    @Test
    void runAsSystemWindowIsSilent() {
        RlsContext.runAsSystem(() ->
            entityManager.createQuery("select d from ReceivingDocument d").getResultList());

        assertThat(RlsStatementGuard.violations()).isEmpty();
        assertThat(RlsStatementGuard.violationCount()).isZero();
    }

    /** Таблица без @RlsDimension (Nomenclature) гейтом не проверяется вовсе. */
    @Test
    void nonRlsEntitySelectIsSilent() {
        entityManager.createQuery("select n from Nomenclature n").getResultList();

        assertThat(RlsStatementGuard.violations()).isEmpty();
        assertThat(RlsStatementGuard.violationCount()).isZero();
    }
}
