package org.ip.application.register;

import jakarta.persistence.EntityManager;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.*;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.crud.MetadataDrivenAggregateSaveService.SectionInput;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.EventSource;
import org.ipro.register.RegisterSliceReader;
import org.ipro.rls.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Р9: стоимость чтения среза — точечное чтение и batch-срез на набор полных ключей.
 *
 * <p><b>Что проверяется.</b> Приёмка Р9 требует трёх разных утверждений, и они сделаны отдельными
 * тестами, а не одним «комплексным»: batch совпадает с точечным oracle по каждому ключу
 * (включая вычет собственного регистратора и отсутствие строки), одиночное чтение не платит
 * count, а batch не растёт с числом ключей. Последний пункт измеряется счётчиком SQL, а не
 * обещанием: Hibernate держит один {@code StatementInspector}, поэтому на время теста он
 * подменён счётчиком, а абсолютные числа печатаются в отчёт ({@code [Р9]}).</p>
 *
 * <p><b>Почему и запись тоже здесь.</b> Документ учёта читает срез на каждую свою строку, поэтому
 * «нет линейного N+1» проверяется не только на API чтения, но и на сохранении документа с
 * несколькими позициями: чтение среза измеряется как SELECT по таблице регистра и не должно
 * расти с числом позиций. Данные фикстуры уникальны (UUID в кодах), как и в пилотном IT.</p>
 */
@SpringBootTest(classes = Application.class, properties = {
    "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "org.ip.application.register.RegisterBatchReadIT$CountingInspector"})
class RegisterBatchReadIT {

    private static final LocalDateTime DATE = LocalDateTime.of(2026, 10, 6, 10, 0);
    private static final String REGISTER_TABLE = "nomenclature_accounting_object";

    @MockitoBean
    private DataInitializer demo;

    @Autowired
    private RegisterSliceReader<NomenclatureAccountingObject> slice;

    @Autowired
    private AccountingObjectRegisterService reader;

    @Autowired
    private AccountingObjectSaveService documents;

    @Autowired
    private MetadataDrivenAggregateSaveService aggregates;

    @Autowired
    private EntityDataAccess access;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AccessGrantRepository grants;

    @Autowired
    private RlsFilterActivator filters;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        String username = RlsTestFixture.authenticateAsSuperuser(grants);
        // Wildcard остаётся построчным обходом: canonical read проверяет read-ключ типа отдельно.
        RlsTestFixture.grantC5EntityRead(grants, username, Workshop.class, Nomenclature.class,
            AccountingType.class, UnitOfMeasurement.class, AccountingObjectInstallation.class,
            AccountingObjectCorrection.class, NomenclatureAccountingObject.class);
        fixture = new TransactionTemplate(transactionManager).execute(status -> {
            filters.ensureRlsEnabled(entityManager);
            String key = UUID.randomUUID().toString().substring(0, 8);
            Workshop workshop = access.create(Workshop.class, new Workshop("w" + key, "Цех Р9"));
            UnitOfMeasurement unit = access.create(UnitOfMeasurement.class,
                new UnitOfMeasurement("u" + key, "Штуки", "u" + key));
            List<Nomenclature> nomenclatures = new ArrayList<>();
            for (int index = 1; index <= 7; index++) {
                Nomenclature nomenclature = new Nomenclature("n" + key + "-" + index, "Изделие " + index, unit);
                nomenclatures.add(aggregates.save(nomenclature, List.of(), EventSource.UI).aggregate());
            }
            AccountingType fifo = access.create(AccountingType.class, new AccountingType("f" + key, "ФИФО"));
            AccountingType lifo = access.create(AccountingType.class, new AccountingType("l" + key, "ЛИФО"));
            entityManager.flush();

            // Шесть объектов учёта одной установкой: строки документа — тот самый «набор ключей».
            AccountingObjectInstallation installation = new AccountingObjectInstallation();
            installation.setNumber("I-" + key);
            installation.setWorkshop(workshop);
            installation.setDate(DATE);
            documents.save(installation, List.of(SectionInput.attached(AccountingObjectInstallationItem.class,
                IntStream.range(0, 6).mapToObj(index -> installationItem(nomenclatures.get(index), fifo)).toList())),
                EventSource.SYSTEM);

            // Корректировка трёх номенклатур: она же — прогон batch-чтения в записи документа.
            AccountingObjectCorrection correction = new AccountingObjectCorrection();
            correction.setNumber("C-" + key);
            correction.setWorkshop(workshop);
            correction.setDate(DATE.plusDays(1));
            AccountingObjectCorrection saved = documents.save(correction,
                List.of(SectionInput.attached(AccountingObjectCorrectionItem.class,
                    IntStream.range(0, 3).mapToObj(index -> correctionItem(nomenclatures.get(index), lifo)).toList())),
                EventSource.SYSTEM).aggregate();
            return new Fixture(workshop, List.copyOf(nomenclatures), fifo, lifo, saved);
        });
    }

    @AfterEach
    void clearSubject() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    /**
     * Batch обязан давать ровно то же, что точечный oracle, по каждому ключу: исправленные
     * номенклатуры — последний тип, неисправленные — прежний, номенклатура без строки среза —
     * отсутствие. Это parity, а не «примерно совпадает».
     */
    @Test
    void batchEqualsPointReadOracleForEveryKeyIncludingAbsence() {
        AccountingObjectCorrection draft = draft(DATE.plusDays(2));
        List<Long> keys = ids();

        Map<Long, AccountingType> batch = reader.currentTypes(draft, keys);

        assertThat(keys).hasSize(7);
        for (int index = 0; index < keys.size(); index++) {
            Long nomenclatureId = keys.get(index);
            AccountingType oracle = reader.currentType(draft, nomenclatureId).orElse(null);
            assertThat(batch.get(nomenclatureId))
                .as("batch должен совпасть с точечным чтением для номенклатуры %d", index + 1)
                .isEqualTo(oracle);
        }
        assertThat(batch).hasSize(6);
        assertThat(batch.get(fixture.nomenclatures().get(0).getId())).isEqualTo(fixture.lifo());
        assertThat(batch.get(fixture.nomenclatures().get(2).getId())).isEqualTo(fixture.lifo());
        assertThat(batch.get(fixture.nomenclatures().get(3).getId())).isEqualTo(fixture.fifo());
        assertThat(batch).doesNotContainKey(fixture.nomenclatures().get(6).getId());
    }

    /**
     * Правило «своя строка не считается предыдущей» batch обязан соблюдать так же, как точечное
     * чтение: у корректировки на {@code DATE + 1} предыдущий тип — установка, а не её собственная
     * строка. Иначе батч «ускорил» бы чтение, изменив семантику среза.
     */
    @Test
    void batchExcludesTheEditableDocumentsOwnRecorderLikeThePointRead() {
        AccountingObjectCorrection correction =
            access.detail(AccountingObjectCorrection.class, fixture.correction().getId()).orElseThrow();

        Map<Long, AccountingType> batch = reader.currentTypes(correction,
            List.of(fixture.nomenclatures().get(0).getId()));

        assertThat(batch.get(fixture.nomenclatures().get(0).getId())).isEqualTo(fixture.fifo());
        assertThat(batch.get(fixture.nomenclatures().get(0).getId()))
            .isEqualTo(reader.currentType(correction, fixture.nomenclatures().get(0).getId()).orElseThrow());
    }

    /**
     * Измерение, а не обещание: batch из шести ключей стоит столько же SQL, сколько batch из
     * одного, а точечный канал платит за каждый ключ. Точка дополнительно не платит count —
     * поэтому одиночное чтение дешевле страницы {@code (0, 1)}, у которой total нужен по контракту.
     */
    @Test
    void batchDoesNotGrowWithKeyCountAndPointReadDoesNotPayCount() {
        AccountingObjectCorrection draft = draft(DATE.plusDays(2));
        List<Long> keys = ids().subList(0, 6);

        int batchOne = statements(() -> reader.currentTypes(draft, List.of(keys.getFirst())));
        int batchSix = statements(() -> reader.currentTypes(draft, keys));
        int pointOne = statements(() -> reader.currentType(draft, keys.getFirst()));
        int pointSix = statements(() -> keys.forEach(key -> reader.currentType(draft, key)));
        int pageOne = statements(() -> reader.latest(fixture.workshop(), DATE.plusDays(2), PageRequest.of(0, 1)));

        System.out.println("[Р9] batch 1 ключ: " + batchOne + " SQL, batch 6 ключей: " + batchSix
            + " SQL, точечное 1: " + pointOne + " SQL, точечных 6: " + pointSix
            + " SQL, страница (0,1): " + pageOne + " SQL");

        assertThat(batchSix).as("измерение не должно быть вакуумным").isGreaterThan(0);
        assertThat(batchSix).as("batch не зависит от числа ключей: линейный N+1 исключён")
            .isEqualTo(batchOne);
        assertThat(pointOne).as("одиночное чтение не считает total, который не использует")
            .isLessThan(pageOne);
        assertThat(pointSix).as("точечный канал платит за каждый ключ")
            .isGreaterThan(batchSix);
    }

    /**
     * Строки документа читают срез один раз на документ: SELECT по таблице регистра при сохранении
     * корректировки не растёт с числом позиций. Вставки строк регистра не считаются — измеряется
     * именно стоимость чтения.
     */
    @Test
    void correctionSaveReadsTheSliceOnceRegardlessOfLineCount() {
        long oneLine = sliceSelects(() -> saveCorrection(List.of(fixture.nomenclatures().get(0)), DATE.plusDays(3)));
        long sixLines = sliceSelects(() -> saveCorrection(fixture.nomenclatures().subList(0, 6), DATE.plusDays(4)));

        System.out.println("[Р9] SELECT по таблице регистра при сохранении корректировки: 1 позиция — "
            + oneLine + ", 6 позиций — " + sixLines);

        assertThat(oneLine).as("сохранение корректировки обязано прочитать срез").isGreaterThan(0);
        assertThat(sixLines).as("чтение среза строк документа не линейно по числу позиций")
            .isEqualTo(oneLine);
    }

    /** Лимиты, дубли, пустой набор и неверная форма ключа — отказ до данных, а не тихое чтение. */
    @Test
    void batchRefusesMalformedKeysAndReadsDuplicatesOnce() {
        assertThat(slice.latestForKeys(DATE, List.of())).isEmpty();

        assertThatThrownBy(() -> slice.latestForKeys(DATE, List.of(Map.of("workshop", fixture.workshop().getId()))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SLICE_BATCH_KEY");
        assertThatThrownBy(() -> slice.latestForKeys(DATE, List.of(key(fixture.nomenclatures().get(0), 1L))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SLICE_BATCH_KEY");
        assertThatThrownBy(() -> slice.latestForKeys(DATE, List.of(
            Map.of("workshop", "строка", "nomenclature", fixture.nomenclatures().get(0).getId()))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SLICE_VALUE_TYPE");

        List<Map<String, Object>> tooMany = IntStream.range(0, RegisterSliceReader.MAX_BATCH_KEYS + 1)
            .mapToObj(index -> Map.<String, Object>of("workshop", fixture.workshop().getId(),
                "nomenclature", 900_000_000L + index))
            .toList();
        assertThatThrownBy(() -> slice.latestForKeys(DATE, tooMany))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SLICE_BATCH_SIZE");

        List<Map<String, Object>> duplicated = List.of(key(fixture.nomenclatures().get(0), null),
            key(fixture.nomenclatures().get(0), null), key(fixture.nomenclatures().get(5), null),
            key(fixture.nomenclatures().get(5), null));
        List<Map<String, Object>> distinct = List.of(key(fixture.nomenclatures().get(0), null),
            key(fixture.nomenclatures().get(5), null));
        assertThat(slice.latestForKeys(DATE, duplicated))
            .as("два разных ключа на дату установки дают две строки, а не четыре")
            .hasSize(2);
        assertThat(statements(() -> slice.latestForKeys(DATE, duplicated)))
            .as("дубль ключа не удваивает чтение")
            .isEqualTo(statements(() -> slice.latestForKeys(DATE, distinct)));
    }

    /** Запись прошла через batch: строки регистра исправленных номенклатур не изменили свой ресурс. */
    @Test
    void correctionDocumentsKeepTheirOwnTypeAndPreviousTypeFromTheBatchRead() {
        List<AccountingObjectCorrectionItem> items = new TransactionTemplate(transactionManager).execute(status ->
            entityManager.createQuery("select i from AccountingObjectCorrectionItem i join fetch i.currentAccountingType"
                    + " join fetch i.accountingType where i.document.id = :id", AccountingObjectCorrectionItem.class)
                .setParameter("id", fixture.correction().getId()).getResultList());

        assertThat(items).hasSize(3).allSatisfy(item -> {
            assertThat(item.getAccountingType().getId()).as("свой ресурс корректировки").isEqualTo(fixture.lifo().getId());
            assertThat(item.getCurrentAccountingType().getId()).as("предыдущий тип из batch-чтения")
                .isEqualTo(fixture.fifo().getId());
        });
        assertThat(reader.history(fixture.workshop(), PageRequest.of(0, 50)).getContent()).hasSize(9);
    }

    // === фикстура ===

    private record Fixture(Workshop workshop, List<Nomenclature> nomenclatures, AccountingType fifo,
                           AccountingType lifo, AccountingObjectCorrection correction) { }

    private static AccountingObjectInstallationItem installationItem(Nomenclature nomenclature, AccountingType type) {
        AccountingObjectInstallationItem item = new AccountingObjectInstallationItem();
        item.setNomenclature(nomenclature);
        item.setAccountingType(type);
        return item;
    }

    private static AccountingObjectCorrectionItem correctionItem(Nomenclature nomenclature, AccountingType type) {
        AccountingObjectCorrectionItem item = new AccountingObjectCorrectionItem();
        item.setNomenclature(nomenclature);
        item.setAccountingType(type);
        return item;
    }

    private AccountingObjectCorrection draft(LocalDateTime date) {
        AccountingObjectCorrection draft = new AccountingObjectCorrection();
        draft.setWorkshop(fixture.workshop());
        draft.setDate(date);
        return draft;
    }

    private void saveCorrection(List<Nomenclature> nomenclatures, LocalDateTime date) {
        AccountingObjectCorrection document = new AccountingObjectCorrection();
        document.setNumber("C-" + UUID.randomUUID().toString().substring(0, 8));
        document.setWorkshop(fixture.workshop());
        document.setDate(date);
        documents.save(document, List.of(SectionInput.attached(AccountingObjectCorrectionItem.class,
            nomenclatures.stream().map(nomenclature -> correctionItem(nomenclature, fixture.lifo())).toList())),
            EventSource.SYSTEM);
    }

    private List<Long> ids() {
        return fixture.nomenclatures().stream().map(Nomenclature::getId).toList();
    }

    /** Ключ батча: полный набор измерений регистра; {@code extra} добавляет лишнее измерение. */
    private Map<String, Object> key(Nomenclature nomenclature, Long extra) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("workshop", fixture.workshop().getId());
        key.put("nomenclature", nomenclature.getId());
        if (extra != null) key.put("extra", extra);
        return key;
    }

    // === замер ===

    /**
     * SQL одного окна измерения. Окно открывается в транзакции: общий {@code EntityManager} не
     * принимает {@code clear()} без неё, а без очистки первый уровень кеша выдал бы ноль SQL.
     */
    private int statements(Runnable action) {
        return new TransactionTemplate(transactionManager).execute(status -> measure(action).total());
    }

    /** SELECT по таблице регистра: чтение среза, а не вставка его строк. */
    private long sliceSelects(Runnable action) {
        Counter counter = new TransactionTemplate(transactionManager).execute(status -> measure(action));
        long selects = counter.statements().stream()
            .filter(statement -> statement.stripLeading().toLowerCase(Locale.ROOT).startsWith("select"))
            .filter(statement -> statement.toLowerCase(Locale.ROOT).contains(REGISTER_TABLE))
            .count();
        System.out.println("[Р9] SQL окна: " + counter.total() + ", из них SELECT по " + REGISTER_TABLE + ": " + selects);
        return selects;
    }

    private Counter measure(Runnable action) {
        entityManager.clear();
        CountingInspector.begin();
        try {
            action.run();
            return CountingInspector.stop();
        } catch (RuntimeException | Error error) {
            CountingInspector.stop();
            throw error;
        } finally {
            entityManager.clear();
        }
    }

    /**
     * Счётчик SQL: Hibernate держит один {@code StatementInspector}, поэтому на время теста он
     * заменён целиком и регистрируется по имени класса (public no-arg конструктор). Доступ к окну
     * замера — через статический {@link ThreadLocal}.
     */
    public static final class CountingInspector implements StatementInspector {

        private static final ThreadLocal<Counter> CURRENT = new ThreadLocal<>();

        static void begin() {
            CURRENT.set(new Counter());
        }

        static Counter stop() {
            Counter counter = CURRENT.get();
            CURRENT.remove();
            return counter == null ? new Counter() : counter;
        }

        @Override
        public String inspect(String sql) {
            Counter counter = CURRENT.get();
            if (counter != null && sql != null) {
                counter.record(sql);
            }
            return sql;
        }
    }

    /** SQL одного окна измерения. */
    static final class Counter {

        private final List<String> statements = new ArrayList<>();

        void record(String sql) {
            statements.add(sql);
        }

        int total() {
            return statements.size();
        }

        List<String> statements() {
            return List.copyOf(statements);
        }
    }
}
