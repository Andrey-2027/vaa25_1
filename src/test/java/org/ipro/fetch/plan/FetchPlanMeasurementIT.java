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
import org.ip.model.PrdSpecMtr;
import org.ip.repository.ReceivingDocumentRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ip.repository.WorkshopRepository;
import org.ipro.crud.ServiceLocator;
import org.ipro.data.CanonicalEntityService;
import org.ipro.crud.LookupService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
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
 * <p>«До» реконструируется в тесте тем же кодом, который строил граф до подключения
 * registry (metadata-производный набор
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
    private ServiceLocator serviceLocator;

    @Autowired
    private LookupService lookupService;

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
     * Граф списка ровно в том виде, в каком он строился до подключения плана (§C3.4) —
     * эталон для сравнения «до/после».
     */
    private List<String> legacyListPaths(Class<?> entityClass) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        return FetchGraphs.deepen(entityClass,
            FetchGraphs.entityReferencePaths(meta.getGridFields()), metadataResolver,
            InstanceNameBridge::instanceNamePaths);
    }

    /** C4.6 волна B: у {@code ReceivingDocument} нет typed-сервиса — резолв идёт canonical handle. */
    private CanonicalEntityService<ReceivingDocument> documents() {
        return (CanonicalEntityService<ReceivingDocument>) serviceLocator
            .<ReceivingDocument, Long>findService(ReceivingDocument.class);
    }

    /** C4.6 волна E: у {@code PrdSpec} тоже остался только canonical handle. */
    private CanonicalEntityService<PrdSpec> prdSpecs() {
        return (CanonicalEntityService<PrdSpec>) serviceLocator
            .<PrdSpec, Long>findService(PrdSpec.class);
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
        long legacyQueries = countStatements(statistics, () -> documents().findAll(
            null, PageRequest.of(0, 10), legacyListPaths(ReceivingDocument.class)));

        entityManager.clear();
        long planQueries = countStatements(statistics, () -> documents().findAll(
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

        var rows = documents().findAll(null, PageRequest.of(0, 10)).getContent();
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

        PrdSpec loaded = prdSpecs().findById(spec.getId()).orElseThrow();

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

    /**
     * Обратная сторона того же контракта: значение выбора читается сценарием {@code LOOKUP},
     * и только он несёт объявленные {@code @Lookup(fetch)} зависимости (единицу измерения
     * выбранной номенклатуры). Пока lookup по id шёл планом {@code DETAIL}, эти зависимости
     * терялись, и detached-рендер значения выбора мог дойти до lazy load — тот класс дефектов,
     * который C3 закрывал.
     */
    @Test
    void lookupByIdKeepsTheSelectionDependenciesThatTheFormPlanOmits() {
        UnitOfMeasurement unit = unitOfMeasurementRepository.save(
            new UnitOfMeasurement("LU" + suffix(), "Метр", "PLU" + suffix()));
        Nomenclature nomenclature = nomenclatureRepository.save(
            new Nomenclature("LN" + suffix(), "Деталь выбора", unit));
        Journal journal = journal("LJ" + suffix());
        PrdSpec spec = new PrdSpec();
        spec.setJournal(journal);
        spec.setNomenclature(nomenclature);
        spec.setCodeSpec("LC" + suffix());
        prdSpecRepository.saveAndFlush(spec);
        entityManager.clear();

        PrdSpec lookedUp = lookupService.findById(PrdSpec.class, spec.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(lookedUp.getNomenclature()))
            .as("ссылка значения выбора обязана быть загружена")
            .isTrue();
        assertThat(Hibernate.isInitialized(lookedUp.getNomenclature().getUnitOfMeasurement()))
            .as("lookup по id не должен терять объявленные @Lookup(fetch) зависимости")
            .isTrue();
    }

    /**
     * Blank lookup — не фильтр, но и не «вычитать справочник и обрезать в памяти»: limit
     * обязан уходить в SQL. Без {@code setMaxResults} запрос загрузил бы все 25 строк, что
     * и проверяется счётчиком загрузок сущностей.
     */
    @Test
    void blankLookupLimitsInSqlInsteadOfAfterLoadingTheTable() {
        Statistics statistics = statistics();
        UnitOfMeasurement unit = unitOfMeasurementRepository.save(
            new UnitOfMeasurement("BU" + suffix(), "Метр", "PBU" + suffix()));
        for (int i = 0; i < 25; i++) {
            nomenclatureRepository.save(new Nomenclature("BL" + i + suffix(),
                "Заготовка " + i, unit));
        }
        entityManager.flush();
        entityManager.clear();

        long before = statistics.getEntityLoadCount();
        List<Nomenclature> rows = lookupService.search(Nomenclature.class,
            new String[]{"code", "name"}, "", 5);
        long loaded = statistics.getEntityLoadCount() - before;

        assertThat(rows).hasSize(5);
        assertThat(loaded)
            .as("limit должен применяться в SQL, а не stream().limit() после полной загрузки")
            .isLessThan(25);
    }

    /**
     * Paging parity с Spring Data: если Specification помечает запрос distinct (join по
     * to-many коллекции), totalElements тоже обязан быть distinct — иначе счётчик
     * завышается на дубликаты строк.
     *
     * <p>Фикстура намеренно проверяется отдельно: без двух строк join вырождается и тест
     * перестал бы что-либо утверждать. На H2 тест зелёный и без явного {@code countDistinct}
     * (H2 сворачивает {@code select distinct count(...)}); на PostgreSQL та же запись
     * дубликаты не убирает, поэтому отказ от явного {@code countDistinct} — регрессия
     * именно в production-диалекте, и тест фиксирует контракт, а не оптимизацию H2.</p>
     */
    @Test
    void pagingCountIsDistinctWhenTheSpecificationJoinsACollection() {
        Journal journal = journal("CJ" + suffix());
        UnitOfMeasurement unit = unitOfMeasurementRepository.save(
            new UnitOfMeasurement("CU" + suffix(), "Метр", "PCU" + suffix()));
        Nomenclature nomenclature = nomenclatureRepository.save(
            new Nomenclature("CN" + suffix(), "Деталь счётчика", unit));
        PrdSpec spec = new PrdSpec();
        spec.setJournal(journal);
        spec.setNomenclature(nomenclature);
        spec.setCodeSpec("CC" + suffix());
        prdSpecRepository.saveAndFlush(spec);
        for (int i = 0; i < 2; i++) {
            PrdSpecMtr row = new PrdSpecMtr();
            row.setTypeMtr(0);
            row.setPrdSpec(spec);
            entityManager.persist(row);
        }
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.createQuery(
                "select count(m) from PrdSpecMtr m where m.prdSpec.id = :id", Long.class)
            .setParameter("id", spec.getId())
            .getSingleResult())
            .as("фикстура обязана иметь две строки, иначе parity нечего проверять")
            .isEqualTo(2L);

        Specification<PrdSpec> joinsMaterials = (root, query, cb) -> {
            query.distinct(true);
            return cb.equal(root.join("materials").get("typeMtr"), 0);
        };

        Page<PrdSpec> page = prdSpecs().findAll(joinsMaterials, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements())
            .as("join по коллекции не должен считать дубликаты строк")
            .isEqualTo(1);
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
