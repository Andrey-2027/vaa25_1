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
import org.ip.repository.ReceivingDocumentRepository;
import org.ip.repository.WorkshopRepository;
import org.ipro.crud.LookupService;
import org.ipro.crud.ServiceLocator;
import org.ipro.data.CanonicalEntityService;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.ipro.rls.c5.C5AccessDeniedException;
import org.ipro.rls.c5.C5PermissionKeys;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Строгий read-гейт типа целиком на реальных сервисах полного контекста: доступ к виду
 * («можно ли читать ReceivingDocument вообще») решает C5 read-ключом
 * {@code ENTITY:<FQN>} — один раз и тем же ответом, что и плитка подсистемы.
 *
 * <p>До C5.7 решение дублировалось: гейт читал флаг {@code canRead} короткого ключа
 * {@code ENTITY:ReceivingDocument}, то есть ключа <b>операций</b> типа. Теперь строка гранта на
 * этом ключе чтение не открывает — она про CREATE/UPDATE/DELETE (ADR-0017 §3), а без C5 read-ключа
 * чтение пусто или отказывает, даже если построчные FILTERABLE-измерения (JOURNAL/BRANCH) строку
 * пропускают. {@code LookupService} — независимый Criteria-путь чтения — подчиняется тому же гейту.</p>
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
    private org.ip.repository.UnitOfMeasurementRepository unitOfMeasurementRepository;


    @Autowired
    private ServiceLocator serviceLocator;

    /** C4.6: у {@code Nomenclature} больше нет typed-сервиса — резолв идёт canonical-хэндлом. */
    private CanonicalEntityService<Nomenclature> nomenclatures() {
        return (CanonicalEntityService<Nomenclature>) serviceLocator
            .<Nomenclature, Long>findService(Nomenclature.class);
    }

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

    /** C4.6 волна B: у {@code ReceivingDocument} нет typed-сервиса — резолв идёт canonical handle. */
    private CanonicalEntityService<ReceivingDocument> documents() {
        return (CanonicalEntityService<ReceivingDocument>) serviceLocator
            .<ReceivingDocument, Long>findService(ReceivingDocument.class);
    }

    /** Доступ к виду целиком даёт C5 entity-read — строки на коротком ENTITY-ключе не нужны. */
    @Test
    void c5EntityReadAloneUnblocksReads() {
        bootstrapProtectedWrites();
        Branch branch = branch("UG-B");
        Workshop receiver = workshop("UG-W1", branch);
        Workshop deliverer = workshop("UG-W2", branch);
        Journal journal = journal("UG-J");
        ReceivingDocument doc = document("UG-1", journal, receiver, deliverer);

        grant("dave", "JOURNAL", journal.getId(), true, false, false);
        grant("dave", "BRANCH", branch.getId(), true, false, false);
        RlsTestFixture.grantC5EntityRead(accessGrantRepository, "dave", ReceivingDocument.class);
        loginAs("dave");

        assertThat(documents().findAll()).extracting(ReceivingDocument::getNumber)
            .containsExactly("UG-1");
        assertThat(documents().findAll(PageRequest.of(0, 10))).extracting(ReceivingDocument::getNumber)
            .containsExactly("UG-1");
        assertThat(documents().findById(doc.getId())).isPresent();
    }

    /**
     * P-U: ключ один, но флаги независимы. Строка с одним write-флагом чтение не открывает,
     * и гейт отказывает до построчной оценки (даже когда JOURNAL/BRANCH строку пропускают).
     */
    @Test
    void aWriteOnlyGrantOnTheUnifiedEntityKeyDoesNotOpenReads() {
        bootstrapProtectedWrites();
        Branch branch = branch("C5-B");
        Workshop receiver = workshop("C5-W1", branch);
        Workshop deliverer = workshop("C5-W2", branch);
        Journal journal = journal("C5-J");
        document("C5-1", journal, receiver, deliverer);

        grant("dave", "JOURNAL", journal.getId(), true, false, false);
        grant("dave", "BRANCH", branch.getId(), true, false, false);
        grant("dave", C5PermissionKeys.entity(ReceivingDocument.class), null, false, true, false);
        loginAs("dave");

        assertThatThrownBy(() -> documents().findAll())
            .isInstanceOf(C5AccessDeniedException.class);
        assertThatThrownBy(() -> lookupService.findAll(ReceivingDocument.class))
            .isInstanceOf(C5AccessDeniedException.class);
    }

    /** Lookup-путь подчиняется тому же решению: C5 entity-read открывает и его. */
    @Test
    void lookupServiceHonorsReadGate() {
        bootstrapProtectedWrites();
        Branch branch = branch("LK-B");
        Workshop receiver = workshop("LK-W1", branch);
        Workshop deliverer = workshop("LK-W2", branch);
        Journal journal = journal("LK-J");
        ReceivingDocument doc = document("LK-1", journal, receiver, deliverer);

        grant("dave", "JOURNAL", journal.getId(), true, false, false);
        grant("dave", "BRANCH", branch.getId(), true, false, false);
        grant("dave", C5PermissionKeys.entity(ReceivingDocument.class), null, false, true, false);
        loginAs("dave");

        assertThatThrownBy(() -> lookupService.findAll(ReceivingDocument.class))
            .isInstanceOf(C5AccessDeniedException.class);

        RlsTestFixture.grantC5EntityRead(accessGrantRepository, "dave", ReceivingDocument.class);

        assertThat(lookupService.findAll(ReceivingDocument.class))
            .extracting(ReceivingDocument::getNumber).containsExactly("LK-1");
        assertThat(lookupService.findById(ReceivingDocument.class, doc.getId())).isPresent();
        assertThat(lookupService.search(ReceivingDocument.class, new String[]{"number"}, "LK", 10))
            .extracting(ReceivingDocument::getNumber).containsExactly("LK-1");
    }

    /**
     * C5 entity-read обязателен и для типа без CHECK_ONLY RLS policy. Запись при этом решает
     * отдельное право на операцию (ADR-0017): у типа без измерений RLS не участвует, но
     * canonical write без операционной строки всё равно отказывает.
     */
    @Test
    void entitiesWithoutCheckOnlyDimensionsAreUnaffected() {
        loginAs("mallory");
        RlsTestFixture.grantC5EntityRead(accessGrantRepository, "mallory", Nomenclature.class);
        grant("mallory", C5PermissionKeys.entityOperations(Nomenclature.class), null, false, true, true);
        UnitOfMeasurement uom = new UnitOfMeasurement("шт", "Штука", "RG-UOM");
        unitOfMeasurementRepository.save(uom);
        Nomenclature nomenclature = new Nomenclature("RG-N", "Номенклатура без RLS", uom);
        Nomenclature saved = nomenclatures().save(nomenclature);

        assertThat(nomenclatures().findAll()).extracting(Nomenclature::getId)
            .contains(saved.getId());
    }

    private void bootstrapProtectedWrites() {
        grant("bootstrap", "*", null, true, true, true);
        loginAs("bootstrap");
    }
}
