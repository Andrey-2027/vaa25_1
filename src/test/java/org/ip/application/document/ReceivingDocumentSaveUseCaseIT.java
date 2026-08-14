package org.ip.application.document;

import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.model.ReceivingDocument;
import org.ip.model.Workshop;
import org.ip.repository.JournalRepository;
import org.ip.repository.ReceivingDocumentRepository;
import org.ip.repository.WorkshopRepository;
import org.ip.service.ReceivingDocumentItemService;
import org.ipro.rls.RlsContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class ReceivingDocumentSaveUseCaseIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @MockitoSpyBean
    private ReceivingDocumentItemService itemService;

    @Autowired
    private ReceivingDocumentSaveUseCase useCase;

    @Autowired
    private ReceivingDocumentRepository documentRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Test
    void rollsBackHeaderWhenReplacingRowsFails() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String number = "TX-" + suffix;

        RlsContext.runAsSystem(() -> {
            Workshop receiving = workshopRepository.save(new Workshop("RW-" + suffix, "Receiving " + suffix));
            Workshop transferring = workshopRepository.save(new Workshop("TW-" + suffix, "Transferring " + suffix));
            Journal journal = new Journal();
            journal.setCode("J-" + suffix);
            journal.setName("Journal " + suffix);
            journalRepository.save(journal);

            ReceivingDocument document = new ReceivingDocument(number, LocalDate.now(), receiving, transferring);
            document.setJournal(journal);
            doThrow(new IllegalStateException("Forced row persistence failure"))
                .when(itemService).replaceAll(any(ReceivingDocument.class), anyList());

            assertThatThrownBy(() -> useCase.save(new ReceivingDocumentSaveCommand(document, List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Forced row persistence failure");

            assertThat(documentRepository.findAll())
                .noneMatch(saved -> number.equals(saved.getNumber()));
        });
    }
}
