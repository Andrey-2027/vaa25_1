package org.ip.security;

import org.ip.config.DataInitializer;
import org.ip.model.Branch;
import org.ip.model.Journal;
import org.ip.model.ReceivingDocument;
import org.ip.model.Workshop;
import org.ip.repository.BranchRepository;
import org.ip.repository.JournalRepository;
import org.ip.repository.WorkshopRepository;
import org.ip.service.ReceivingDocumentService;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsAccessDeniedException;
import org.ipro.rls.RlsTestFixture;
import org.ipro.telemetry.core.SecurityEventLogger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Фаза 8 RLS-плана: отказ прав на общей RLS-границе (RlsPolicyEnforcer через
 * repository-aspect) фиксируется SECURITY-событием "rls:denied" через durable-путь
 * SecurityEventLogger — до броска RlsAccessDeniedException. Полезно для журнала
 * админки (SECURITY хранится 1 год):
 * видно, кто и по какому измерению пытался изменить запись.
 */
@SpringBootTest
class RlsDeniedEventTest {

    @MockitoBean
    private DataInitializer dataInitializer;

    @MockitoSpyBean
    private SecurityEventLogger securityEventLogger;

    @Autowired
    private ReceivingDocumentService receivingDocumentService;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void writeGuardDeniedEmitsRlsSecurityEvent() {
        String deniedUser = "rls-denied-event-test";
        ReceivingDocument doc = RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            Branch branch = new Branch();
            branch.setCode("DE-1");
            branch.setName("Филиал");
            branchRepository.save(branch);

            Workshop receiver = new Workshop("DE-A", "Цех А");
            receiver.setBranch(branch);
            workshopRepository.save(receiver);

            Workshop deliverer = new Workshop("DE-B", "Цех Б");
            deliverer.setBranch(branch);
            workshopRepository.save(deliverer);

            Journal journal = new Journal();
            journal.setCode("DEJ");
            journal.setName("Журнал");
            journalRepository.save(journal);

            // Подготовка должна пройти; отказ проверяем именно по ENTITY-гранту.
            accessGrantRepository.save(grant(deniedUser, "BRANCH"));
            accessGrantRepository.save(grant(deniedUser, "JOURNAL"));

            ReceivingDocument document = new ReceivingDocument(
                "DE-1", LocalDate.now(), receiver, deliverer);
            document.setJournal(journal);
            return document;
        });

        SecurityContext deniedContext = SecurityContextHolder.createEmptyContext();
        deniedContext.setAuthentication(
            new UsernamePasswordAuthenticationToken(deniedUser, "n/a", java.util.List.of()));
        SecurityContextHolder.setContext(deniedContext);

        assertThatThrownBy(() -> receivingDocumentService.create(doc))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("Нет прав на изменение");

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(securityEventLogger)
            .emitSecurityEvent(eq("WARN"), eq("rls:denied"), anyString(),
                org.mockito.ArgumentMatchers.contains("Нет прав на изменение"), payload.capture());

        // конкретное измерение зависит от порядка checks и состояния bootstrap (грантов
        // JOURNAL/BRANCH может и не быть в разделяемой тестовой БД) — проверяем состав payload,
        // а не конкретный dimension
        assertThat((String) payload.getValue().get("dimension")).isNotBlank();
        assertThat(payload.getValue().get("action")).isEqualTo("изменение");
        assertThat(payload.getValue().get("entity")).isEqualTo(ReceivingDocument.class.getName());
    }

    private static AccessGrant grant(String subjectKey, String dimension) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(subjectKey);
        grant.setDimension(dimension);
        grant.setCanRead(true);
        grant.setCanUpdate(true);
        return grant;
    }
}
