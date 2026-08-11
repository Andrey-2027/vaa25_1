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
import org.ip.service.ValidationException;
import org.ipro.telemetry.core.SecurityEventLogger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Фаза 8 RLS-плана: отказ прав в write-guard'е сервиса (checkRls) фиксируется
 * SECURITY-событием "rls:denied" через durable-путь SecurityEventLogger — до
 * броска ValidationException. Полезно для журнала админки (SECURITY хранится 1 год):
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

    @Test
    void writeGuardDeniedEmitsRlsSecurityEvent() {
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

        ReceivingDocument doc = new ReceivingDocument("DE-1", LocalDate.now(), receiver, deliverer);
        doc.setJournal(journal);

        assertThatThrownBy(() -> receivingDocumentService.create(doc))
            .isInstanceOf(ValidationException.class)
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
}