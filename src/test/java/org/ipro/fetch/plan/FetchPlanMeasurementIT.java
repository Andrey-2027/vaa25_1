package org.ipro.fetch.plan;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.ip.config.DataInitializer;
import org.ip.model.Branch;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.Workshop;
import org.ip.repository.BranchRepository;
import org.ip.repository.JournalRepository;
import org.ip.repository.NomenclatureRepository;
import org.ip.repository.PrdSpecRepository;
import org.ip.repository.ReceivingDocumentRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ip.repository.WorkshopRepository;
import org.ip.service.PrdSpecService;
import org.ip.service.ReceivingDocumentService;
import org.ipro.fetch.instance.InstanceNameBridge;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FetchGraphs;
import org.ipro.metadata.MetadataResolver;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C3.6: измерения. План не должен быть дороже графа, который read-граница строила до C3.4,
 * а detached-рендер не должен инициировать lazy load.
 *
 * <p>«До» реконструируется в тесте тем же кодом, который стоял в
 * {@code AbstractBaseService} до подключения registry (metadata-производный набор
 * ссылочных колонок плюс углубление через состав имени), и затем сравнивается с планом на
 * одних и тех же данных: состав путей и количество подготовленных запросов.</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
@Transactional
class FetchPlanMeasurementIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private FetchPlanRegistry fetchPlanRegistry;

    @Autowired
    private MetadataResolver metadataResolver;

    @Autowired
    private ReceivingDocumentService documentService;

    @Autowired
    private PrdSpecService prdSpecService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private EntityManager entityManager;

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
    private PrdSpecRepository prdSpecRepository;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void loginAsSuperuser() {
        String username = "plan-measure-" + UUID.randomUUID();
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

    /**
     * Граф списка ровно в том виде, в каком его строил {@code AbstractBaseService} до
     * подключения плана (§C3.4) — эталон для сравнения «до/после».
     */
    private List<String> legacyListPaths(Class<?> entityClass) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        return FetchGraphs.deepen(entityClass,
            FetchGraphs.entityReferencePaths(meta.getGridFields()), metadataResolver,
            InstanceNameBridge::instanceNamePaths);
    }

    @Test
    void planCoversAtLeastTheLegacyGraph() {
        List<String> legacy = legacyListPaths(ReceivingDocument.class);
        List<String> plan = fetchPlanRegistry.paths(ReceivingDocument.class, FetchScenario.LIST);

        assertThat(legacy).as("эталон не должен быть пустым, иначе измерение бессмысленно")
            .isNotEmpty();
        assertThat(plan)
            .as("план списка не должен терять ни одного пути прежнего графа")
            .containsAll(legacy);
    }

    @Test
    void planDoesNotCostMoreQueriesThanTheLegacyGraph() {
        Statistics statistics = statistics();
        assertThat(statistics.isStatisticsEnabled())
            .as("измерение требует hibernate.generate_statistics=true")
            .isTrue();

        persistDocuments(3);
        entityManager.flush();

        entityManager.clear();
        long legacyQueries = countStatements(statistics, () -> documentService.findAll(
            null, PageRequest.of(0, 10), legacyListPaths(ReceivingDocument.class)));

        entityManager.clear();
        long planQueries = countStatements(statistics, () -> documentService.findAll(
            null, PageRequest.of(0, 10)));

        assertThat(legacyQueries).as("эталон должен выполнять хотя бы один запрос").isPositive();
        assertThat(planQueries)
            .as("план не должен быть дороже прежнего графа по количеству запросов "
                + "(legacy=" + legacyQueries + ", plan=" + planQueries + ")")
            .isLessThanOrEqualTo(legacyQueries);
    }

    @Test
    void detachedRenderAddsNoQueries() {
        Statistics statistics = statistics();
        assertThat(statistics.isStatisticsEnabled()).isTrue();

        persistDocuments(3);
        entityManager.flush();
        entityManager.clear();

        var rows = documentService.findAll(null, PageRequest.of(0, 10)).getContent();
        assertThat(rows).isNotEmpty();

        long afterLoad = statistics.getPrepareStatementCount();
        for (ReceivingDocument row : rows) {
            // Ровно то, что делает рендер строки: имя журнала и обоих цехов.
            row.getJournal().getCode();
            InstanceNameBridge.displayName(row.getReceivingWorkshop());
            InstanceNameBridge.displayName(row.getTransferringWorkshop());
        }
        long renderQueries = statistics.getPrepareStatementCount() - afterLoad;

        assertThat(renderQueries)
            .as("detached-рендер не должен инициировать lazy load")
            .isZero();
        assertThat(Hibernate.isInitialized(rows.get(0).getJournal())).isTrue();
    }

    @Test
    void detailPlanDoesNotLoadAnotherScenariosDependencies() {
        UnitOfMeasurement unit = unitOfMeasurementRepository.save(
            new UnitOfMeasurement("MU" + suffix(), "Метр", "PUM" + suffix()));
        Nomenclature nomenclature = nomenclatureRepository.save(
            new Nomenclature("MN" + suffix(), "Деталь измерения", unit));
        Journal journal = journal("MJ" + suffix());
        PrdSpec spec = new PrdSpec();
        spec.setJournal(journal);
        spec.setNomenclature(nomenclature);
        spec.setCodeSpec("MC" + suffix());
        prdSpecRepository.saveAndFlush(spec);
        entityManager.clear();

        PrdSpec loaded = prdSpecService.findById(spec.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(loaded.getNomenclature()))
            .as("ссылка формы должна быть загружена планом DETAIL")
            .isTrue();
        assertThat(Hibernate.isInitialized(loaded.getNomenclature().getUnitOfMeasurement()))
            .as("зависимость сценария выбора не должна грузиться формой")
            .isFalse();
        assertThat(Hibernate.isInitialized(loaded.getMaterials()))
            .as("коллекция, не входящая ни в один сценарий, не должна грузиться")
            .isFalse();
    }

    // === Вспомогательное ===

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private long countStatements(Statistics statistics, Runnable action) {
        long before = statistics.getPrepareStatementCount();
        action.run();
        return statistics.getPrepareStatementCount() - before;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private void persistDocuments(int count) {
        Branch branch = branch("MB" + suffix());
        Workshop receiver = workshop("MW1" + suffix(), branch);
        Workshop deliverer = workshop("MW2" + suffix(), branch);
        Journal journal = journal("MK" + suffix());
        for (int i = 0; i < count; i++) {
            ReceivingDocument document = new ReceivingDocument(
                "M-" + suffix(), LocalDate.now(), receiver, deliverer);
            document.setJournal(journal);
            documentRepository.save(document);
        }
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
