package org.ipro.numbering;

import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.Oper;
import org.ip.model.ReceivingDocument;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.Workshop;
import org.ip.repository.JournalRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ip.repository.WorkshopRepository;
import org.ip.service.NomenclatureService;
import org.ip.service.OperService;
import org.ip.service.ReceivingDocumentService;
import org.ipro.rls.RlsContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест движка нумерации: настоящие бины (NumberingService +
 * NumberingCounterService с REQUIRES_NEW/PESSIMISTIC_WRITE), H2, авто-конфигурация.
 * {@code @DirtiesContext(BEFORE_EACH_TEST_METHOD)} — каждый метод получает свежую
 * create-drop БД: счётчики и правила не просачиваются между тестами (ключи-счётчики
 * были бы уникальны и без этого благодаря пер-журнальному scope, но изоляция важнее).
 *
 * Пилот ReceivingDocument: {@code @Numbered(scope="JOURNAL", period=YEAR, prefix="РН-",
 * pattern="{prefix}{yyyy}-{seq:000000}")}.
 */
@SpringBootTest(classes = org.ip.Application.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class NumberingEngineIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private NumberingService numberingService;

    @Autowired
    private NumberingRuleService ruleService;

    @Autowired
    private ReceivingDocumentService documentService;

    @Autowired
    private NomenclatureService nomenclatureService;

    @Autowired
    private OperService operService;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private UnitOfMeasurementRepository uomRepository;

    private static final LocalDate DATE_2025 = LocalDate.of(2025, 6, 1);
    private static final LocalDate DATE_2026 = LocalDate.of(2026, 3, 1);

    private static final Field NUMBER_FIELD = fieldOf(ReceivingDocument.class, "number");

    private static Field fieldOf(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ последовательности

    @Test
    void journalScopeIsolatesSequencesAndYearPeriodRestartsCounter() {
        Journal journal = persistedJournal("J-1");

        // одна журналовая серия, год 2025
        assertThat(numberingService.autoValue(doc(journal, DATE_2025), NUMBER_FIELD))
            .isEqualTo("РН-2025-000001");
        assertThat(numberingService.autoValue(doc(journal, DATE_2025), NUMBER_FIELD))
            .isEqualTo("РН-2025-000002");

        // новый год — новая серия (period входит в ключ счётчика)
        assertThat(numberingService.autoValue(doc(journal, DATE_2026), NUMBER_FIELD))
            .isEqualTo("РН-2026-000001");
    }

    @Test
    void anotherJournalHasItsOwnCounter() {
        Journal first = persistedJournal("J-A");
        Journal second = persistedJournal("J-B");

        assertThat(numberingService.autoValue(doc(first, DATE_2026), NUMBER_FIELD))
            .isEqualTo("РН-2026-000001");
        assertThat(numberingService.autoValue(doc(second, DATE_2026), NUMBER_FIELD))
            .isEqualTo("РН-2026-000001");
    }

    // ----------------------------------------------------------------- runtime-правило

    @Test
    void numberingRuleOverridesPrefixPatternAndInitialValue() {
        NumberingRule rule = new NumberingRule();
        rule.setEntityClass("ReceivingDocument");
        rule.setFieldName("number");
        rule.setPeriod(NumberingPeriod.YEAR);
        rule.setPrefix("ТТН-");
        rule.setPattern("{prefix}{seq:0000}");
        rule.setInitialValue(10L);
        ruleService.save(rule);

        Journal journal = persistedJournal("J-R1");
        assertThat(numberingService.autoValue(doc(journal, DATE_2026), NUMBER_FIELD))
            .isEqualTo("ТТН-0011");
        assertThat(numberingService.autoValue(doc(journal, DATE_2026), NUMBER_FIELD))
            .isEqualTo("ТТН-0012");
    }

    @Test
    void prefixAndPatternDoNotRebindTheSequence() {
        // admin меняет только префикс/шаблон — серия продолжается: prefix/pattern в ключ
        // счётчика НЕ входят (в отличие от period/scope)
        NumberingRule rule = ruleService.save(ruleFor("", "{seq:000000}", NumberingPeriod.YEAR, null));

        Journal journal = persistedJournal("J-R2");
        assertThat(numberingService.autoValue(doc(journal, DATE_2026), NUMBER_FIELD))
            .isEqualTo("000001");

        // смена префикса И шаблона (чтобы префикс попал в номер) — тот же счётчик продолжается
        rule.setPrefix("РН-");
        rule.setPattern("{prefix}{seq:000000}");
        ruleService.save(rule);
        assertThat(numberingService.autoValue(doc(journal, DATE_2026), NUMBER_FIELD))
            .isEqualTo("РН-000002"); // серия не перезапустилась: 2, а не 1
    }

    // -------------------------------------------------------------- ручной ввод / авто

    @Test
    void manualInputSkippedWhenFieldAlreadyFilled() {
        Journal journal = persistedJournal("J-M1");
        ReceivingDocument document = doc(journal, DATE_2026);
        document.setNumber("СВОЙ-01");

        assertThat(numberingService.autoValue(document, NUMBER_FIELD)).isNull();
    }

    @Test
    void manualInputForcedWhenRuleDisablesManualInput() {
        ruleService.save(ruleFor("", "{seq:000000}", NumberingPeriod.YEAR, null, false));

        Journal journal = persistedJournal("J-M2");
        ReceivingDocument document = doc(journal, DATE_2026);
        document.setNumber("префил");

        assertThat(numberingService.autoValue(document, NUMBER_FIELD)).isEqualTo("000001");
    }

    // ------------------------------------------------ admin: правка текущего значения

    @Test
    void setCurrentValueThenNextContinuesFromIt() {
        Journal journal = persistedJournal("J-C1");
        numberingService.setCurrentValue(doc(journal, DATE_2026), NUMBER_FIELD, 100);

        assertThat(numberingService.autoValue(doc(journal, DATE_2026), NUMBER_FIELD))
            .isEqualTo("РН-2026-000101");
    }

    @Test
    void currentValueIsLastIssuedAndHasNoSideEffects() {
        Journal journal = persistedJournal("J-C2");

        // счётчик ещё не создан — 0
        assertThat(numberingService.currentValue(doc(journal, DATE_2026), NUMBER_FIELD)).isZero();

        assertThat(numberingService.autoValue(doc(journal, DATE_2026), NUMBER_FIELD))
            .isEqualTo("РН-2026-000001");
        assertThat(numberingService.currentValue(doc(journal, DATE_2026), NUMBER_FIELD)).isEqualTo(1);

        // повторное чтение ничего не резервирует
        assertThat(numberingService.currentValue(doc(journal, DATE_2026), NUMBER_FIELD)).isEqualTo(1);

        numberingService.setCurrentValue(doc(journal, DATE_2026), NUMBER_FIELD, 100);
        assertThat(numberingService.currentValue(doc(journal, DATE_2026), NUMBER_FIELD)).isEqualTo(100);
    }

    // ----------------------------------------------------------------- конкуренция

    @Test
    void firstCounterRaceProducesUniqueNumbersWithoutFailures() throws Exception {
        Journal journal = persistedJournal("J-X");
        LocalDate fixedDate = LocalDate.of(2026, 3, 1);
        int threads = 16;
        int perThread = 25;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int idx = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    List<String> local = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        // каждый вызов — НОВЫЙ документ с тем же журналом/датой → тот же ключ счётчика
                        local.add(numberingService.autoValue(doc(journal, fixedDate), NUMBER_FIELD));
                    }
                    return local;
                }));
            }
            start.countDown();

            List<String> all = new ArrayList<>();
            for (Future<List<String>> future : futures) {
                all.addAll(future.get());
            }

            assertThat(all).hasSize(threads * perThread);
            assertThat(all).doesNotHaveDuplicates();
            // первому выделению соответствует первый номер серии — значит гонка первого
            // счётчика была пройдена без постоянных падений (иначе count/уникальность бы нарушились)
            assertThat(all).contains("РН-2026-000001");
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ create/update хук

    @Test
    void createAssignsNumberUpdateKeepsIt() {
        RlsContext.runAsSystem(() -> {
            Workshop receiving = workshopRepository.save(new Workshop("RW-1", "Цех приёмщик"));
            Workshop transferring = workshopRepository.save(new Workshop("TW-1", "Цех сдатчик"));
            Journal journal = persistedJournal("J-H1");

            ReceivingDocument document = new ReceivingDocument(null, DATE_2026, receiving, transferring);
            document.setJournal(journal);

            ReceivingDocument saved = documentService.create(document);
            String firstNumber = saved.getNumber();
            assertThat(firstNumber).matches("РН-\\d{4}-\\d{6}");

            saved.setDate(LocalDate.of(2026, 5, 5)); // правка существующего — не перенумеровывает
            ReceivingDocument updated = documentService.update(saved);

            assertThat(updated.getNumber()).isEqualTo(firstNumber);
        });
    }

    @Test
    void manualNumberPreservedOnCreate() {
        RlsContext.runAsSystem(() -> {
            Workshop receiving = workshopRepository.save(new Workshop("RW-2", "Цех приёмщик"));
            Workshop transferring = workshopRepository.save(new Workshop("TW-2", "Цех сдатчик"));
            Journal journal = persistedJournal("J-H2");

            ReceivingDocument document = new ReceivingDocument("МНУЧ-015", DATE_2026, receiving, transferring);
            document.setJournal(journal);

            assertThat(documentService.create(document).getNumber()).isEqualTo("МНУЧ-015");
        });
    }

    @Test
    void nomenclatureCreateFillsGlobalCode() {
        UnitOfMeasurement unit = new UnitOfMeasurement();
        unit.setCode("PC");
        unit.setShortCode("шт");
        unit.setName("Штука");
        uomRepository.save(unit);

        Nomenclature nomenclature = new Nomenclature(null, "Деталь", unit);
        assertThat(nomenclatureService.create(nomenclature).getCode()).matches("\\d{6}");
    }

    @Test
    void operCreateFillsGlobalCodePilot() {
        Oper first = new Oper();
        first.setName("Фрезерная");
        assertThat(operService.create(first).getCode()).matches("\\d{6}");

        Oper second = new Oper();
        second.setName("Сварочная");
        assertThat(operService.create(second).getCode()).matches("\\d{6}");
        assertThat(second.getCode()).isNotEqualTo(first.getCode());

        // ручной код не перезаписывается (allowManual по умолчанию)
        Oper manual = new Oper();
        manual.setName("Ручная");
        manual.setCode("OP-MANUAL");
        assertThat(operService.create(manual).getCode()).isEqualTo("OP-MANUAL");
    }

    // --------------------------------------------------------------------- helpers

    private Journal persistedJournal(String code) {
        Journal journal = new Journal();
        journal.setCode(code);
        journal.setName("Журнал " + code);
        return journalRepository.save(journal);
    }

    /** Документ-заготовка для {@link NumberingService} — НЕ сохраняется (нужен только scope/дата). */
    private ReceivingDocument doc(Journal journal, LocalDate date) {
        ReceivingDocument document = new ReceivingDocument(null, date,
            new Workshop("RW-X", "Цех приёмщик"), new Workshop("TW-X", "Цех сдатчик"));
        document.setJournal(journal);
        return document;
    }

    private NumberingRule ruleFor(String prefix, String pattern, NumberingPeriod period, Long initialValue) {
        return ruleFor(prefix, pattern, period, initialValue, true);
    }

    private NumberingRule ruleFor(String prefix, String pattern, NumberingPeriod period,
                                  Long initialValue, boolean manualInput) {
        NumberingRule rule = new NumberingRule();
        rule.setEntityClass("ReceivingDocument");
        rule.setFieldName("number");
        rule.setPeriod(period);
        rule.setPrefix(prefix);
        rule.setPattern(pattern);
        rule.setInitialValue(initialValue);
        rule.setManualInput(manualInput);
        return rule;
    }
}