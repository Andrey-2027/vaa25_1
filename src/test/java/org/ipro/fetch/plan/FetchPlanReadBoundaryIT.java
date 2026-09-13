package org.ipro.fetch.plan;

import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.ip.config.DataInitializer;
import org.ip.model.Branch;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.Workshop;
import org.ip.repository.BranchRepository;
import org.ip.repository.JournalRepository;
import org.ip.repository.NomenclatureRepository;
import org.ip.repository.ReceivingDocumentRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ip.repository.WorkshopRepository;
import org.ip.service.ReceivingDocumentService;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C3.4: сценарии {@code LIST}, {@code DETAIL} и {@code ROW} потребляются существующей
 * read-границей — {@code AbstractBaseService} (список/форма) и
 * {@code GenericOwnedSectionService} (строка табличной части), а не собираются в этих
 * классах заново.
 *
 * <p>Проверка поведенческая: без применённого плана ссылки пришли бы
 * неинициализированными прокси, поэтому {@code Hibernate.isInitialized} является
 * наблюдаемым признаком того, что граф действительно из плана, а не из локального
 * перечисления полей.</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
@Transactional
class FetchPlanReadBoundaryIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private FetchPlanRegistry fetchPlanRegistry;

    @Autowired
    private ReceivingDocumentService documentService;

    @Autowired
    private GenericOwnedSectionService sectionService;

    @Autowired
    private SectionMetadataRegistry sectionRegistry;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private UnitOfMeasurementRepository unitOfMeasurementRepository;

    @Autowired
    private NomenclatureRepository nomenclatureRepository;

    @Autowired
    private ReceivingDocumentRepository documentRepository;

    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void loginAsSuperuser() {
        String username = "plan-boundary-" + UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));

        AccessGrant wildcard = new AccessGrant();
        wildcard.setSubjectType(AccessGrant.SubjectType.USER);
        wildcard.setSubjectKey(username);
        wildcard.setDimension("*");
        wildcard.setCanRead(true);
        wildcard.setCanUpdate(true);
        wildcard.setCanDelete(true);
        accessGrantRepository.saveAndFlush(wildcard);
    }

    @Test
    void scenarioPlansComeFromMetadataDeclarations() {
        assertThat(fetchPlanRegistry.paths(ReceivingDocument.class, FetchScenario.LIST))
            .contains("journal", "receivingWorkshop", "transferringWorkshop");
        assertThat(fetchPlanRegistry.paths(ReceivingDocument.class, FetchScenario.DETAIL))
            .contains("journal", "receivingWorkshop", "transferringWorkshop");
        assertThat(fetchPlanRegistry.paths(ReceivingDocumentItem.class, FetchScenario.ROW))
            .contains("nomenclature")
            .doesNotContain("journal", "transferringWorkshop");
    }

    @Test
    void findByIdAppliesDetailPlan() {
        ReceivingDocument document = persistDocument();
        entityManager.flush();
        entityManager.clear();

        ReceivingDocument loaded = documentService.findById(document.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(loaded.getJournal())).isTrue();
        assertThat(Hibernate.isInitialized(loaded.getReceivingWorkshop())).isTrue();
        assertThat(Hibernate.isInitialized(loaded.getTransferringWorkshop())).isTrue();
    }

    @Test
    void listQueryAppliesListPlan() {
        ReceivingDocument document = persistDocument();
        entityManager.flush();
        entityManager.clear();

        List<ReceivingDocument> rows = documentService
            .findAll(null, PageRequest.of(0, 10)).getContent()
            .stream()
            .filter(row -> row.getId().equals(document.getId()))
            .toList();

        assertThat(rows).hasSize(1);
        assertThat(Hibernate.isInitialized(rows.get(0).getReceivingWorkshop())).isTrue();
        assertThat(Hibernate.isInitialized(rows.get(0).getTransferringWorkshop())).isTrue();
    }

    @Test
    void simpleAndPagedFindAllOverloadsApplyListPlan() {
        ReceivingDocument document = persistDocument();
        entityManager.flush();
        entityManager.clear();

        ReceivingDocument unpaged = documentService.findAll().stream()
            .filter(row -> row.getId().equals(document.getId()))
            .findFirst().orElseThrow();
        assertThat(Hibernate.isInitialized(unpaged.getReceivingWorkshop())).isTrue();
        assertThat(Hibernate.isInitialized(unpaged.getTransferringWorkshop())).isTrue();

        entityManager.clear();
        ReceivingDocument paged = documentService.findAll(PageRequest.of(0, 10)).getContent().stream()
            .filter(row -> row.getId().equals(document.getId()))
            .findFirst().orElseThrow();
        assertThat(Hibernate.isInitialized(paged.getReceivingWorkshop())).isTrue();
        assertThat(Hibernate.isInitialized(paged.getTransferringWorkshop())).isTrue();

        entityManager.clear();
        ReceivingDocument explicitEmpty = documentService.findAll(null, PageRequest.of(0, 10), List.of())
            .getContent().stream()
            .filter(row -> row.getId().equals(document.getId()))
            .findFirst().orElseThrow();
        assertThat(Hibernate.isInitialized(explicitEmpty.getReceivingWorkshop()))
            .as("дополнительные пути не должны выключать базовый LIST plan")
            .isTrue();
    }

    @Test
    void sectionRowsApplyRowPlan() {
        Branch branch = branch("RB");
        Workshop receiver = workshop("RW1", branch);
        Workshop deliverer = workshop("RW2", branch);
        Journal journal = journal("RJ");
        ReceivingDocument document = documentRepository.save(
            new ReceivingDocument("R-" + suffix(), LocalDate.now(), receiver, deliverer));
        document.setJournal(journal);
        documentRepository.saveAndFlush(document);

        UnitOfMeasurement unit = unitOfMeasurementRepository.save(
            new UnitOfMeasurement("SG" + suffix(), "Штука", "PSG" + suffix()));
        Nomenclature nomenclature = nomenclatureRepository.save(
            new Nomenclature("SN" + suffix(), "Позиция", unit));

        ReceivingDocumentItem item = new ReceivingDocumentItem(nomenclature, BigDecimal.ONE);
        item.setDocument(document);
        entityManager.persist(item);
        entityManager.flush();
        entityManager.clear();

        TableSectionMetadataInfo descriptor =
            sectionRegistry.findByRow(ReceivingDocumentItem.class).orElseThrow();
        List<ReceivingDocumentItem> rows = sectionService.findByParent(
            documentRepository.findById(document.getId()).orElseThrow(), descriptor);

        assertThat(rows).hasSize(1);
        assertThat(Hibernate.isInitialized(rows.get(0).getNomenclature())).isTrue();
    }

    @Test
    void emptyExplicitSectionPathsDoNotDisableRowPlan() {
        Branch branch = branch("RB" + suffix());
        Workshop receiver = workshop("RW1" + suffix(), branch);
        Workshop deliverer = workshop("RW2" + suffix(), branch);
        ReceivingDocument document = documentRepository.saveAndFlush(
            new ReceivingDocument("R-" + suffix(), LocalDate.now(), receiver, deliverer));
        UnitOfMeasurement unit = unitOfMeasurementRepository.save(
            new UnitOfMeasurement("SG" + suffix(), "Штука", "PSG" + suffix()));
        Nomenclature nomenclature = new Nomenclature("SN" + suffix(), "Позиция", unit);
        entityManager.persist(nomenclature);
        ReceivingDocumentItem item = new ReceivingDocumentItem(nomenclature, BigDecimal.ONE);
        item.setDocument(document);
        entityManager.persist(item);
        entityManager.flush();
        entityManager.clear();

        TableSectionMetadataInfo descriptor = sectionRegistry.findByRow(ReceivingDocumentItem.class)
            .orElseThrow();
        ReceivingDocument parent = documentRepository.findById(document.getId()).orElseThrow();
        List<ReceivingDocumentItem> rows = sectionService.findByParent(parent, descriptor, List.of());

        assertThat(rows).hasSize(1);
        assertThat(Hibernate.isInitialized(rows.get(0).getNomenclature()))
            .as("явные dynamic paths расширяют, но не заменяют ROW plan")
            .isTrue();
    }

    // === Вспомогательное ===

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private ReceivingDocument persistDocument() {
        Branch branch = branch("FB" + suffix());
        Workshop receiver = workshop("FW1" + suffix(), branch);
        Workshop deliverer = workshop("FW2" + suffix(), branch);
        Journal journal = journal("FJ" + suffix());
        ReceivingDocument document = new ReceivingDocument(
            "F-" + suffix(), LocalDate.now(), receiver, deliverer);
        document.setJournal(journal);
        return documentRepository.save(document);
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
}
