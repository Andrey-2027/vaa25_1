package org.ip.security;

import org.ip.config.DataInitializer;
import org.ip.model.Branch;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.ReceivingDocument;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.Workshop;
import org.ip.repository.BranchRepository;
import org.ip.repository.JournalRepository;
import org.ip.repository.NomenclatureRepository;
import org.ip.repository.ReceivingDocumentRepository;
import org.ip.repository.WorkshopRepository;
import org.ip.service.LookupService;
import org.ip.service.NomenclatureService;
import org.ip.service.ReceivingDocumentService;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Строгий read-гейт CHECK_ONLY (Фаза 5 RLS-плана) на реальных сервисах полного
 * контекста: без ENTITY-гранта findById/findAll пусты ДАЖЕ если строки проходят по
 * построчным FILTERABLE-измерениям (JOURNAL/BRANCH); LookupService (независимый
 * Criteria-путь чтения) подчиняется тому же гейту.
 */
@SpringBootTest
@Transactional
class RlsReadGateTest {

    /**
     * Сидинг стартовых данных имеет известный баг при свежей БД (detached Role) — вне
     * скоупа этой фазы; проверке read-гейта не нужен.
     */
    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private ReceivingDocumentRepository documentRepository;

    @Autowired
    private NomenclatureRepository nomenclatureRepository;

    @Autowired
    private org.ip.repository.UnitOfMeasurementRepository unitOfMeasurementRepository;

    @Autowired
    private ReceivingDocumentService documentService;

    @Autowired
    private NomenclatureService nomenclatureService;

    @Autowired
    private LookupService lookupService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private AccessGrant grant(String user, String dimension, Long valueId,
                              boolean read, boolean update, boolean delete) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(user);
        grant.setDimension(dimension);
        grant.setDimensionValueId(valueId);
        grant.setCanRead(read);
        grant.setCanUpdate(update);
        grant.setCanDelete(delete);
        return accessGrantRepository.save(grant);
    }

    private Branch branch(String code) {
        Branch branch = new Branch();
        branch.setCode(code);
        branch.setName("Филиал " + code);
        return branchRepository.save(branch);
    }

    private Workshop workshop(String code, Branch branch) {
        Workshop workshop = new Workshop(code, code);
        workshop.setBranch(branch);
        return workshopRepository.save(workshop);
    }

    private Journal journal(String code) {
        Journal journal = new Journal();
        journal.setCode(code);
        journal.setName("Журнал " + code);
        return journalRepository.save(journal);
    }

    private ReceivingDocument document(String number, Journal journal, Workshop receiver, Workshop deliverer) {
        ReceivingDocument doc = new ReceivingDocument(number, LocalDate.now(), receiver, deliverer);
        doc.setJournal(journal);
        return documentRepository.save(doc);
    }

    /**
     * План Ф5: «нет ENTITY-гранта → findById/findAll пусты, даже если строки проходят
     * по JOURNAL/BRANCH» — dave читает Журнал и Филиал документа (обоих!), но без
     * "ENTITY:ReceivingDocument" список и запись пусты.
     */
    @Test
    void noEntityGrantMakesReadsEmptyEvenWhenRowsPassJournalAndBranchFilters() {
        Branch branch = branch("RG-B");
        Workshop receiver = workshop("RG-W1", branch);
        Workshop deliverer = workshop("RG-W2", branch);
        Journal journal = journal("RG-J");
        ReceivingDocument doc = document("RG-1", journal, receiver, deliverer);

        grant("dave", "JOURNAL", journal.getId(), true, false, false);
        grant("dave", "BRANCH", branch.getId(), true, false, false);
        loginAs("dave");

        assertThat(documentService.findAll()).isEmpty();
        assertThat(documentService.findAll(PageRequest.of(0, 10))).isEmpty();
        assertThat(documentService.findById(doc.getId())).isEmpty();
    }

    /** План Ф5 (контр-проверка): ENTITY-грант снимает блокировку — те же строки видны. */
    @Test
    void entityGrantUnblocksReads() {
        Branch branch = branch("UG-B");
        Workshop receiver = workshop("UG-W1", branch);
        Workshop deliverer = workshop("UG-W2", branch);
        Journal journal = journal("UG-J");
        ReceivingDocument doc = document("UG-1", journal, receiver, deliverer);

        grant("dave", "JOURNAL", journal.getId(), true, false, false);
        grant("dave", "BRANCH", branch.getId(), true, false, false);
        grant("dave", "ENTITY:ReceivingDocument", null, true, false, false);
        loginAs("dave");

        assertThat(documentService.findAll()).extracting(ReceivingDocument::getNumber)
            .containsExactly("UG-1");
        assertThat(documentService.findById(doc.getId())).isPresent();
    }

    /** План Ф5: lookup-путь (автокомплит/SelectionForm) подчиняется гейту — симметрично сервису. */
    @Test
    void lookupServiceHonorsReadGate() {
        Branch branch = branch("LK-B");
        Workshop receiver = workshop("LK-W1", branch);
        Workshop deliverer = workshop("LK-W2", branch);
        Journal journal = journal("LK-J");
        ReceivingDocument doc = document("LK-1", journal, receiver, deliverer);

        grant("dave", "JOURNAL", journal.getId(), true, false, false);
        grant("dave", "BRANCH", branch.getId(), true, false, false);
        loginAs("dave");

        assertThat(lookupService.findAll(ReceivingDocument.class)).isEmpty();
        assertThat(lookupService.findById(ReceivingDocument.class, doc.getId())).isEmpty();
        assertThat(lookupService.search(ReceivingDocument.class, new String[]{"number"}, "LK", 10)).isEmpty();

        grant("dave", "ENTITY:ReceivingDocument", null, true, false, false);

        assertThat(lookupService.findAll(ReceivingDocument.class))
            .extracting(ReceivingDocument::getNumber).containsExactly("LK-1");
        assertThat(lookupService.findById(ReceivingDocument.class, doc.getId())).isPresent();
    }

    /** Сущности без CHECK_ONLY-измерений гейтом не затрагиваются вовсе. */
    @Test
    void entitiesWithoutCheckOnlyDimensionsAreUnaffected() {
        loginAs("mallory");
        UnitOfMeasurement uom = new UnitOfMeasurement("шт", "Штука", "RG-UOM");
        unitOfMeasurementRepository.save(uom);
        Nomenclature nomenclature = new Nomenclature("RG-N", "Номенклатура без RLS", uom);
        Nomenclature saved = nomenclatureService.save(nomenclature);

        assertThat(nomenclatureService.findAll()).extracting(Nomenclature::getId)
            .containsExactly(saved.getId());
    }
}