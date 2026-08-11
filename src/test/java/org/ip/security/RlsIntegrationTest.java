package org.ip.security;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.ip.model.Branch;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.Workshop;
import org.ip.repository.UserRepository;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadableIdsCache;
import org.ip.service.WorkshopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Два (+) пользователя с разными {@link AccessGrant} на измерение JOURNAL — против
 * настоящего Hibernate @Filter на H2 (см. src/test/resources/application.properties),
 * а не мок AccessService. Ровно тот сценарий, который раньше проверяли только руками на
 * реальном сервере (см. обсуждение RLS, п.5 плана).
 *
 * Сознательно НЕ поднимает JournalService/PrdSpecService/весь контекст приложения
 * (Vaadin, MetadataResolver и т.п.) — целимся в сам механизм RLS (AccessService +
 * RlsFilterActivator + Hibernate @Filter), а не в UI-слой сервисов. RlsDimensionRegistry/
 * RlsReadableIdsCache/RlsFilterActivator здесь создаются вручную (`new`), не через Spring
 * DI: RlsReadableIdsCache в проде @SessionScope и требует HTTP-сессии, которой в этом
 * тесте нет, а конструкторы у всех трёх классов простые, без Spring-магии внутри.
 */
@DataJpaTest
@EnableJpaRepositories(basePackages = {"org.ip", "org.ipro.rls"})
class RlsIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private org.ip.repository.WorkshopRepository workshopRepository;

    private AccessService accessService;
    private org.ipro.rls.RlsDimensionRegistry registry;
    private RlsReadableIdsCache cache;
    private RlsFilterActivator activator;

    private Long journalAId;
    private Long journalBId;

    @BeforeEach
    void setUp() {
        var registry = new org.ipro.rls.RlsDimensionRegistry("org.ip");
        registry.rebuild();
        this.registry = registry;
        accessService = new AccessService(accessGrantRepository,
            new UserRepositoryRlsRoleResolver(userRepository), registry);
        cache = new RlsReadableIdsCache(accessService);
        activator = new RlsFilterActivator(registry, cache, () -> CurrentUser.username());

        Journal journalA = new Journal();
        journalA.setCode("A");
        journalA.setName("Журнал A");
        entityManager.persist(journalA);

        Journal journalB = new Journal();
        journalB.setCode("B");
        journalB.setName("Журнал B");
        entityManager.persist(journalB);
        entityManager.flush();

        journalAId = journalA.getId();
        journalBId = journalB.getId();

        // alice: прямой грант ЧТЕНИЕ+ИЗМЕНЕНИЕ (без удаления) только на журнал A
        persistGrant(AccessGrant.SubjectType.USER, "alice", "JOURNAL", journalAId, true, true, false);

        // admin: wildcard-грант на любое измерение — полный доступ без построчных записей
        persistGrant(AccessGrant.SubjectType.USER, "admin", "*", null, true, true, true);

        entityManager.flush();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private AccessGrant persistGrant(AccessGrant.SubjectType type, String subjectKey, String dimension,
                                     Long dimensionValueId, boolean read, boolean update, boolean delete) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(type);
        grant.setSubjectKey(subjectKey);
        grant.setDimension(dimension);
        grant.setDimensionValueId(dimensionValueId);
        grant.setCanRead(read);
        grant.setCanUpdate(update);
        grant.setCanDelete(delete);
        entityManager.persist(grant);
        return grant;
    }

    /** Две спецификации, по одной под каждым журналом — общая номенклатура/единица измерения. */
    private void createSpecsUnderBothJournals() {
        UnitOfMeasurement unit = new UnitOfMeasurement();
        unit.setCode("PC");
        unit.setShortCode("шт");
        unit.setName("Штука");
        entityManager.persist(unit);

        Nomenclature nomenclature = new Nomenclature();
        nomenclature.setCode("N1");
        nomenclature.setName("Деталь");
        nomenclature.setUnitOfMeasurement(unit);
        entityManager.persist(nomenclature);

        PrdSpec specUnderA = new PrdSpec();
        specUnderA.setJournal(entityManager.find(Journal.class, journalAId));
        specUnderA.setNomenclature(nomenclature);
        specUnderA.setCodeSpec("SPEC-A");
        entityManager.persist(specUnderA);

        PrdSpec specUnderB = new PrdSpec();
        specUnderB.setJournal(entityManager.find(Journal.class, journalBId));
        specUnderB.setNomenclature(nomenclature);
        specUnderB.setCodeSpec("SPEC-B");
        entityManager.persist(specUnderB);

        entityManager.flush();
    }

    @Test
    void aliceSeesOnlyHerJournal() {
        loginAs("alice");
        activator.ensureRlsEnabled(entityManager);

        List<Journal> visible = entityManager
            .createQuery("select j from Journal j order by j.code", Journal.class)
            .getResultList();

        assertThat(visible).extracting(Journal::getId).containsExactly(journalAId);
    }

    @Test
    void userWithNoGrantsSeesNothing() {
        loginAs("bob"); // нет ни одного AccessGrant
        activator.ensureRlsEnabled(entityManager);

        List<Journal> visible = entityManager
            .createQuery("select j from Journal j", Journal.class)
            .getResultList();

        assertThat(visible).isEmpty();
    }

    @Test
    void adminWildcardSeesEverythingAndFilterStaysDisabled() {
        loginAs("admin");
        activator.ensureRlsEnabled(entityManager);

        Session session = entityManager.unwrap(Session.class);
        // wildcard (dimension = "*") — фильтр сознательно НЕ включаем, а не включаем
        // "разрешить всё" через параметр (см. RlsFilterActivator/AccessService).
        assertThat(session.getEnabledFilter("JOURNAL")).isNull();

        List<Journal> visible = entityManager
            .createQuery("select j from Journal j", Journal.class)
            .getResultList();
        assertThat(visible).hasSize(2);
    }

    @Test
    void prdSpecInheritsAccessFromItsJournalNotItsOwnId() {
        createSpecsUnderBothJournals();

        loginAs("alice");
        activator.ensureRlsEnabled(entityManager);

        List<PrdSpec> visible = entityManager
            .createQuery("select s from PrdSpec s order by s.codeSpec", PrdSpec.class)
            .getResultList();

        assertThat(visible).extracting(PrdSpec::getCodeSpec).containsExactly("SPEC-A");
    }

    @Test
    void canUpdateAndCanDeleteReflectGrantFlagsPerJournal() {
        assertThat(accessService.canUpdate("JOURNAL", journalAId, "alice")).isTrue();
        assertThat(accessService.canUpdate("JOURNAL", journalBId, "alice")).isFalse();
        // у alice canDelete=false даже на "свой" журнал A
        assertThat(accessService.canDelete("JOURNAL", journalAId, "alice")).isFalse();
        assertThat(accessService.canDelete("JOURNAL", journalAId, "admin")).isTrue(); // wildcard
    }

    /**
     * Ключевая проверка для ReferenceCheckService: withRlsDisabled должен видеть ВСЕ
     * строки (включая недоступные текущему пользователю) и корректно восстанавливать
     * фильтр после себя — а не просто "заодно всё равно всё видно, потому что фильтр
     * ещё не был включён".
     */
    @Test
    void withRlsDisabledSeesEverythingAndRestoresFilterAfter() {
        createSpecsUnderBothJournals();

        loginAs("alice");
        activator.ensureRlsEnabled(entityManager); // включает JOURNAL-фильтр на сессии

        Long countIgnoringRls = activator.withRlsDisabled(entityManager, () ->
            entityManager.createQuery("select count(s) from PrdSpec s", Long.class).getSingleResult());
        assertThat(countIgnoringRls).isEqualTo(2L); // обе, несмотря на ограничение alice

        List<PrdSpec> visibleAfter = entityManager
            .createQuery("select s from PrdSpec s", PrdSpec.class)
            .getResultList();
        assertThat(visibleAfter).extracting(PrdSpec::getCodeSpec).containsExactly("SPEC-A"); // фильтр восстановлен
    }

    /**
     * Кэш readable-ids должен пересчитаться после отзыва гранта (версия бампается
     * в AccessGrantChangeListener при flush) — а не молча отдавать старые id до
     * перелогина. Проверяем сам RlsReadableIdsCache, а не через activator: у
     * activator своя, отдельная идемпотентность на уровне EntityManager
     * (ACTIVATED_PROPERTY) — повторный ensureRlsEnabled на том же EntityManager
     * ничего не пересчитает, это другой механизм.
     */
    @Test
    void readableIdsCacheInvalidatesAfterGrantChange() {
        List<Long> before = cache.getReadableIds("JOURNAL", "alice");
        assertThat(before).containsExactly(journalAId);

        List<AccessGrant> aliceGrants = accessGrantRepository.findUserGrants("JOURNAL", "alice");
        entityManager.remove(aliceGrants.get(0));
        entityManager.flush(); // триггерит @PostRemove -> AccessGrantVersion.bump()

        List<Long> after = cache.getReadableIds("JOURNAL", "alice");
        assertThat(after).containsExactly(AccessService.NO_ACCESS_SENTINEL);
    }

    // ------------------------------------------------------- Филиал (BRANCH), п.1 плана

    /** Цех с указанным Филиалом + Цех без Филиала (не участвует в RLS по BRANCH вовсе). */
    private Long[] createTwoWorkshopsOneWithBranch(Long branchId) {
        Workshop withBranch = new Workshop("W1", "Цех с филиалом");
        withBranch.setBranch(entityManager.find(Branch.class, branchId));
        entityManager.persist(withBranch);

        Workshop withoutBranch = new Workshop("W2", "Цех без филиала");
        entityManager.persist(withoutBranch);

        entityManager.flush();
        return new Long[]{withBranch.getId(), withoutBranch.getId()};
    }

    @Test
    void workshopWithoutBranchIsVisibleRegardlessOfBranchGrants() {
        Branch branchA = new Branch();
        branchA.setCode("BR-A");
        branchA.setName("Филиал А");
        entityManager.persist(branchA);

        Branch branchB = new Branch();
        branchB.setCode("BR-B");
        branchB.setName("Филиал Б");
        entityManager.persist(branchB);
        entityManager.flush();

        Long[] wsUnderA = createTwoWorkshopsOneWithBranch(branchA.getId());
        Long workshopUnderA = wsUnderA[0];
        Long workshopWithoutBranch = wsUnderA[1];

        Workshop workshopUnderB = new Workshop("W3", "Цех филиала Б");
        workshopUnderB.setBranch(branchB);
        entityManager.persist(workshopUnderB);
        entityManager.flush();

        // грант только на Филиал А
        persistGrant(AccessGrant.SubjectType.USER, "carol", "BRANCH", branchA.getId(), true, true, false);
        entityManager.flush();

        loginAs("carol");
        activator.ensureRlsEnabled(entityManager);

        List<Long> visibleIds = entityManager
            .createQuery("select w.id from Workshop w", Long.class)
            .getResultList();

        // видно: свой Цех (под Филиалом А) + Цех без Филиала вообще (не участвует в BRANCH);
        // НЕ видно: Цех под чужим Филиалом Б.
        assertThat(visibleIds).containsExactlyInAnyOrder(workshopUnderA, workshopWithoutBranch);
    }

    /**
     * Ключевая проверка write-guard после перехода на мульти-измеренческий контракт
     * (RlsDimensionValue.getRlsChecks()): у Цеха без Филиала проверка BRANCH должна быть
     * NotApplicable (пройдена автоматически), а не "null → только wildcard", иначе
     * редактировать Цеха без Филиала мог бы только обладатель wildcard-гранта на BRANCH —
     * это была бы регрессия по сравнению с тем, что видно на чтении.
     */
    @Test
    void workshopWithoutBranchHasNoWriteRestrictionOnBranchDimension() {
        Workshop workshop = new Workshop("W4", "Цех без филиала");
        // carol не имеет НИ ОДНОГО гранта на BRANCH вообще
        assertThat(workshop.getRlsChecks().get("BRANCH"))
            .containsExactly(org.ipro.rls.RlsCheckValue.notApplicable());
    }

    // ------------------------------------------- ReceivingDocument: JOURNAL И BRANCH×2 (п.3 плана)

    private Branch persistBranch(String code, String name) {
        Branch branch = new Branch();
        branch.setCode(code);
        branch.setName(name);
        entityManager.persist(branch);
        return branch;
    }

    private Workshop persistWorkshop(String code, Branch branch) {
        Workshop workshop = new Workshop(code, code);
        workshop.setBranch(branch);
        entityManager.persist(workshop);
        return workshop;
    }

    private ReceivingDocument persistDocument(String number, Journal journal, Workshop receiver, Workshop deliverer) {
        ReceivingDocument doc = new ReceivingDocument(number, java.time.LocalDate.now(), receiver, deliverer);
        doc.setJournal(journal);
        entityManager.persist(doc);
        return doc;
    }

    /**
     * Ключевая проверка сложного условия из плана RLS, п.3: документ виден только когда
     * И Журнал, И Цех-приёмщик (через Филиал), И Цех-сдатчик (через Филиал) проходят —
     * если хотя бы один не проходит, документ не отображается вовсе. Два разных
     * @Filter ("JOURNAL" и "BRANCH") на одной сущности склеиваются Hibernate по AND
     * автоматически — RlsFilterActivator/AccessService не менялись ради этого вообще,
     * только аннотации на ReceivingDocument (см. его javadoc).
     */
    @Test
    void receivingDocumentHiddenWhenEitherWorkshopBranchDoesNotPass() {
        Branch branchA = persistBranch("BR-A", "Филиал А");
        Branch branchB = persistBranch("BR-B", "Филиал Б");

        Workshop workshopA = persistWorkshop("W-A", branchA);
        Workshop workshopB = persistWorkshop("W-B", branchB);
        Workshop workshopNoBranch = persistWorkshop("W-N", null);

        Journal journalA = entityManager.find(Journal.class, journalAId);

        // оба цеха под доступным Филиалом А — должен быть виден
        persistDocument("RD-1", journalA, workshopA, workshopA);
        // сдатчик под НЕдоступным Филиалом Б — должен быть скрыт, несмотря на то что
        // приёмщик и журнал доступны
        persistDocument("RD-2", journalA, workshopA, workshopB);
        // сдатчик без Филиала вообще (не участвует в BRANCH) — должен быть виден
        persistDocument("RD-3", journalA, workshopA, workshopNoBranch);
        entityManager.flush();

        // dave: доступ к Журналу A и только к Филиалу А (не Б)
        persistGrant(AccessGrant.SubjectType.USER, "dave", "JOURNAL", journalAId, true, true, false);
        persistGrant(AccessGrant.SubjectType.USER, "dave", "BRANCH", branchA.getId(), true, true, false);
        // Фаза 5 RLS-плана: строгий read-гейт CHECK_ONLY — для чтения списка Накладных
        // пользователю нужен ENTITY-грант ("ENTITY:ReceivingDocument"); этот тест проверяет
        // построчные фильтры (прямой entityManager-запрос, где гейт не действует), поэтому
        // dave выдаём грант — сценарий должен отражать новую модель прав, а не обходить её.
        persistGrant(AccessGrant.SubjectType.USER, "dave", "ENTITY:ReceivingDocument", null, true, false, false);
        entityManager.flush();

        loginAs("dave");
        activator.ensureRlsEnabled(entityManager);

        List<String> visibleNumbers = entityManager
            .createQuery("select d.number from ReceivingDocument d order by d.number", String.class)
            .getResultList();

        assertThat(visibleNumbers).containsExactly("RD-1", "RD-3");
    }

    /**
     * Тот же документ RD-2 (сдатчик под недоступным Филиалом Б) — доступен по JOURNAL
     * и по Филиалу приёмщика, но НЕ должен пройти canUpdate/canDelete целиком, потому
     * что write-guard требует AND по ВСЕМ проверкам, включая обе стороны BRANCH.
     */
    @Test
    void receivingDocumentRlsChecksRequireBothWorkshopBranchesForWrite() {
        Branch branchA = persistBranch("BR-A2", "Филиал А2");
        Branch branchB = persistBranch("BR-B2", "Филиал Б2");
        Workshop workshopA = persistWorkshop("W-A2", branchA);
        Workshop workshopB = persistWorkshop("W-B2", branchB);
        Journal journalA = entityManager.find(Journal.class, journalAId);
        entityManager.flush();

        ReceivingDocument doc = new ReceivingDocument("RD-X", java.time.LocalDate.now(), workshopA, workshopB);
        doc.setJournal(journalA);

        Map<String, List<org.ipro.rls.RlsCheckValue>> checks = doc.getRlsChecks();
        assertThat(checks.get("JOURNAL")).containsExactly(org.ipro.rls.RlsCheckValue.of(journalAId));
        assertThat(checks.get("BRANCH")).containsExactly(
            org.ipro.rls.RlsCheckValue.of(branchA.getId()),
            org.ipro.rls.RlsCheckValue.of(branchB.getId()));

        // dave имеет грант только на branchA, не на branchB — значит хотя бы одна из
        // двух проверок BRANCH не пройдёт, и это должно блокировать запись целиком
        persistGrant(AccessGrant.SubjectType.USER, "dave2", "JOURNAL", journalAId, true, true, false);
        persistGrant(AccessGrant.SubjectType.USER, "dave2", "BRANCH", branchA.getId(), true, true, false);
        entityManager.flush();

        assertThat(accessService.canUpdate("BRANCH", branchA.getId(), "dave2")).isTrue();
        assertThat(accessService.canUpdate("BRANCH", branchB.getId(), "dave2")).isFalse();
    }

    // --------------------------------------------------- CHECK_ONLY-измерения (расширение плана)

    /**
     * Регрессионная защита: до фикса в RlsFilterActivator CHECK_ONLY-измерение без
     * @FilterDef ронялось бы UnknownFilterException прямо здесь — на ЛЮБОМ вызове
     * ensureRlsEnabled, а не только в новых тестах, потому что ReceivingDocument (уже
     * реальный класс в проекте) теперь объявляет "ENTITY:ReceivingDocument" CHECK_ONLY
     * и попадает в общий реестр измерений автоматически.
     */
    @Test
    void checkOnlyDimensionNeverGetsHibernateFilterEnabled() {
        loginAs("alice");
        activator.ensureRlsEnabled(entityManager);

        Session session = entityManager.unwrap(Session.class);
        assertThat(session.getEnabledFilter("ENTITY:ReceivingDocument")).isNull();
    }

    @Test
    void receivingDocumentRlsChecksIncludeEntityLevelCheckOnlyDimension() {
        Journal journalA = entityManager.find(Journal.class, journalAId);
        Workshop workshop = persistWorkshop("W-E1", null);
        entityManager.flush();

        ReceivingDocument doc = new ReceivingDocument("RD-E1", java.time.LocalDate.now(), workshop, workshop);
        doc.setJournal(journalA);

        assertThat(doc.getRlsChecks().get("ENTITY:ReceivingDocument"))
            .containsExactly(org.ipro.rls.RlsCheckValue.of(null));
    }

    /**
     * "Пользователь может создавать Накладные, но не имеет доступа к Ордерам" — в
     * точности этот механизм: canUpdate по "ENTITY:ReceivingDocument" не зависит от
     * JOURNAL/BRANCH вообще, это отдельное AND-условие в write-guard'е (см.
     * AbstractBaseService.checkRls — не тестируем здесь напрямую, см. javadoc класса, но
     * сама проверка через AccessService — та же самая, что checkRls вызывает внутри).
     */
    @Test
    void entityLevelGrantGatesWriteIndependentlyOfJournalAndBranch() {
        persistGrant(AccessGrant.SubjectType.USER, "erin", "JOURNAL", journalAId, true, true, false);
        entityManager.flush();

        // JOURNAL есть, но "ENTITY:ReceivingDocument" — нет вообще
        assertThat(accessService.canUpdate("ENTITY:ReceivingDocument", null, "erin")).isFalse();

        persistGrant(AccessGrant.SubjectType.USER, "erin", "ENTITY:ReceivingDocument", null, true, true, false);
        entityManager.flush();

        assertThat(accessService.canUpdate("ENTITY:ReceivingDocument", null, "erin")).isTrue();
    }

    @Test
    void hasAnyAccessReflectsCheckOnlyGrantForMenuVisibility() {
        assertThat(accessService.hasAnyAccess("ENTITY:ReceivingDocument", "frank")).isFalse();

        persistGrant(AccessGrant.SubjectType.USER, "frank", "ENTITY:ReceivingDocument", null, true, false, false);
        entityManager.flush();

        assertThat(accessService.hasAnyAccess("ENTITY:ReceivingDocument", "frank")).isTrue();
    }

    // ------------------------------------------------- bootstrap: первая запись измерения

    /**
     * Регрессия "нельзя создать первый Филиал/Журнал": гранты выдаются на существующие
     * значения, а значения появляются только после первой записи. Пока по измерению нет
     * НИ ОДНОГО гранта, создание нового значения (id == null) разрешено (bootstrap).
     *
     * BRANCH в setUp грантов не имеет вообще → создание первого филиала любым
     * пользователем (даже bob'ом без единого гранта) проходит.
     */
    @Test
    void bootstrapAllowsFirstValueCreationWhenDimensionHasNoGrants() {
        assertThat(accessService.canUpdate("BRANCH", null, "bob")).isTrue();
        assertThat(accessService.canUpdate("BRANCH", null, "alice")).isTrue();
    }

    /**
     * Bootstrap отключается, как только по измерению появился ХОТЬ ОДИН грант — создание
     * новых значений дальше только через право на конкретное значение ("управление
     * измерением") или wildcard. JOURNAL в setUp размечен (alice/admin) — bob без грантов
     * создать новый журнал уже не может.
     */
    @Test
    void bootstrapTurnsOffOnceDimensionHasAnyGrant() {
        assertThat(accessService.canUpdate("JOURNAL", null, "bob")).isFalse();
        // alice имеет update на журнал A (конкретное значение) — "внутри" разметки:
        assertThat(accessService.canUpdate("JOURNAL", null, "alice")).isTrue();
    }

    /**
     * Bootstrap НЕ действует на CHECK_ONLY-измерения: "ENTITY:ReceivingDocument" на
     * создание документа — жёсткий гейт (доступ к виду целиком), а не справочник.
     */
    @Test
    void bootstrapDoesNotApplyToCheckOnlyDimensions() {
        assertThat(accessService.canUpdate("ENTITY:ReceivingDocument", null, "bob")).isFalse();
        assertThat(accessService.canUpdate("ENTITY:ReceivingDocument", null, "alice")).isFalse();
    }

    // --------------------------- регрессия listForm Workshop: фильтр колонки + RLS @Filter

    /**
     * Жалоба: ввод значения в фильтр колонки (Код/Наименование) в ListForm Цехов не даёт
     * реакции, а для Ед.изм./Номенклатуры (не под RLS) фильтрует. Kadidat: Criteria-запрос
     * findAllWithFetchGraph с активным Hibernate @Filter BRANCH + Specification от TextFilter.
     * Проверяем ровно путь ListForm: service.findAll(spec, pageable, fetchPaths).
     */
    @Test
    void workshopGridSpecificationStillFiltersWithRlsFilterActive() {
        Branch branchA = persistBranch("BR-A3", "Филиал А3");

        Workshop w1 = new Workshop("W1", "Цех один");
        w1.setBranch(branchA);
        entityManager.persist(w1);
        Workshop w2 = new Workshop("W2", "Цех два");
        entityManager.persist(w2);
        entityManager.flush();

        persistGrant(AccessGrant.SubjectType.USER, "gt", "BRANCH", branchA.getId(), true, true, false);
        entityManager.flush();

        loginAs("gt");
        Long branchId = branchA.getId();
        activator.ensureRlsEnabled(entityManager); // включает BRANCH-фильтр с allowedIds=[branchA]

        WorkshopService service = new WorkshopService(workshopRepository,
            jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "rlsFilterActivator", activator);
        ReflectionTestUtils.setField(service, "rlsReadGate", new org.ipro.rls.RlsReadGate(accessService, registry));

        Specification<Workshop> likeName = (root, query, cb) ->
            cb.like(cb.lower(root.get("name")), "%один%");
        Page<Workshop> page = service.findAll(
            likeName, PageRequest.of(0, 100), List.of("branch"));

        assertThat(branchId).isNotNull();
        assertThat(page.getContent()).extracting(Workshop::getCode).containsExactly("W1");
    }
}