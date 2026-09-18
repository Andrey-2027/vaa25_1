package org.ip.service;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.model.NomSklAttribute;
import org.ip.model.Nomenclature;
import org.ip.model.SklNomOpa;
import org.ip.model.SklNomOpaValue;
import org.ip.model.UnitOfMeasurement;
import org.ip.repository.AttributeTypeRepository;
import org.ip.repository.AttributeValueRepository;
import org.ip.repository.NomSklAttributeRepository;
import org.ip.repository.NomenclatureRepository;
import org.ip.repository.SklNomOpaRepository;
import org.ip.repository.SklNomOpaValueRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ipro.crud.EntityLookup;
import org.ipro.crud.NaturalKeyCreateSupport;
import org.ipro.crud.ReferenceCheckService;
import org.ipro.crud.ValidationException;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ядро атрибутов КСУ (по §5 ispro-attributeset-design-01):
 * <ul>
 *   <li>канонизация комбинации (порядок входной карты не важен, пустые отсеваются);</li>
 *   <li>find-or-create с гонкой (два потока — одна шапка);</li>
 *   <li>ленивый пустой набор и его переиспользование;</li>
 *   <li>привязки «Номенклатура ↔ Тип» — схема, набор — экземпляр;</li>
 *   <li>переименование значения: канон стабилен, displayName пересобирается;</li>
 *   <li>immutable набора (save/create/update/delete закрыты).</li>
 * </ul>
 *
 * <p>Без транзакции теста ({@code NOT_SUPPORTED}): сервис создаёт наборы в собственных
 * REQUIRES_NEW-транзакциях; каждая проверка работает со своими кодами — коммиты соседних
 * тестов не мешают.</p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SklNomOpaServiceTest {

    @Autowired
    private SklNomOpaRepository sklNomOpaRepository;
    @Autowired
    private SklNomOpaValueRepository sklNomOpaValueRepository;
    @Autowired
    private NomSklAttributeRepository nomSklAttributeRepository;
    @Autowired
    private AttributeTypeRepository attributeTypeRepository;
    @Autowired
    private AttributeValueRepository attributeValueRepository;
    @Autowired
    private NomenclatureRepository nomenclatureRepository;
    @Autowired
    private UnitOfMeasurementRepository unitOfMeasurementRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * C4.6 волна E: сервис набора больше не наследует compatibility base. Проверяются
     * канонизация и интернирование — они работают через repository; canonical handle
     * (чтения) в этих сценариях не участвует и подменён заглушкой.
     */
    private SklNomOpaService newSetService() {
        return new SklNomOpaService(
            sklNomOpaRepository, sklNomOpaValueRepository,
            new NaturalKeyCreateSupport(transactionManager),
            mock(org.ipro.data.CanonicalEntityService.class));
    }

    /** C4.6 волна E: тот же шов, что в {@code newSetService} — repository плюс canonical handle. */
    private AttributeValueService newValueService() {
        EntityLookup lookupService = mock(EntityLookup.class);
        when(lookupService.findSelectedById(any(Class.class), any())).thenAnswer(invocation -> {
            Class<?> entityClass = invocation.getArgument(0);
            Object id = invocation.getArgument(1);
            return java.util.Optional.ofNullable(entityManager.find(entityClass, id));
        });
        return new AttributeValueService(
            attributeValueRepository, attributeTypeRepository,
            sklNomOpaRepository, sklNomOpaValueRepository,
            lookupService,
            new ManagedEntityCatalog(entityManager.getEntityManagerFactory()),
            new NaturalKeyCreateSupport(transactionManager), transactionManager,
            mock(org.ipro.data.CanonicalEntityService.class));
    }

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private Nomenclature saveNomenclature(String code) {
        UnitOfMeasurement unit =
            unitOfMeasurementRepository.save(new UnitOfMeasurement("шт" + code, "Штука " + code, "ШТ" + code));
        return nomenclatureRepository.save(new Nomenclature(code, code, unit));
    }

    private AttributeType saveType(String code, AttributeValueType valueType) {
        return attributeTypeRepository.save(new AttributeType(code, code, valueType));
    }

    private AttributeValue saveValue(AttributeType type, String code) {
        return attributeValueRepository.save(
            new AttributeValue(type, code, code, code.toUpperCase(), null));
    }

    /** Вызов findOrCreate в явной транзакции (сервис без Spring-прокси). */
    private SklNomOpa findOrCreateTx(SklNomOpaService service, Nomenclature nomenclature,
                                     Map<AttributeType, AttributeValue> attrs) {
        return new TransactionTemplate(transactionManager).execute(status ->
            service.findOrCreate(nomenclature, attrs));
    }

    // === Канонизация ===

    @Test
    void canonicalIsOrderIndependent() {
        AttributeType color = saveType("ОЦВЕТ", AttributeValueType.STRING);
        AttributeType size = saveType("ОРАЗМ", AttributeValueType.STRING);
        AttributeValue red = saveValue(color, "Красный");
        AttributeValue m = saveValue(size, "M");
        Nomenclature nom = saveNomenclature("N-CAN1");
        SklNomOpaService service = newSetService();

        Map<AttributeType, AttributeValue> a = new HashMap<>();
        a.put(color, red);
        a.put(size, m);
        Map<AttributeType, AttributeValue> b = new HashMap<>();
        b.put(size, m);
        b.put(color, red);

        SklNomOpa setA = findOrCreateTx(service, nom, a);
        SklNomOpa setB = findOrCreateTx(service, nom, b);

        assertThat(setB.getId()).isEqualTo(setA.getId()); // тот же канон — тот же набор
        assertThat(setA.getCanonical()).isEqualTo(color.getId() + ":" + red.getId()
            + ";" + size.getId() + ":" + m.getId()); // сортировка по id типа
    }

    @Test
    void emptyEntriesAreDroppedFromCanonical() {
        AttributeType color = saveType("ОЦВЕТ2", AttributeValueType.STRING);
        AttributeValue red = saveValue(color, "Красный");
        Nomenclature nom = saveNomenclature("N-CAN2");
        SklNomOpaService service = newSetService();

        Map<AttributeType, AttributeValue> attrs = new HashMap<>();
        attrs.put(color, red);
        attrs.put(null, null);            // отсев: пустой тип
        attrs.put(saveType("ОЦВЕТ3", AttributeValueType.STRING), null); // отсев: пустое значение

        SklNomOpa set = findOrCreateTx(service, nom, attrs);
        assertThat(set.getCanonical()).isEqualTo(color.getId() + ":" + red.getId());
    }

    @Test
    void valueFromAnotherTypeRejected() {
        AttributeType color = saveType("ОЦВЕТ4", AttributeValueType.STRING);
        AttributeType size = saveType("ОРАЗМ4", AttributeValueType.STRING);
        AttributeValue m = saveValue(size, "M");
        Nomenclature nom = saveNomenclature("N-CAN3");
        SklNomOpaService service = newSetService();

        Map<AttributeType, AttributeValue> attrs = Map.of(color, m);
        assertThatThrownBy(() -> findOrCreateTx(service, nom, attrs))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("не принадлежит типу");
    }

    @Test
    void oversizeSetRejected() {
        Nomenclature nom = saveNomenclature("N-CAN4");
        SklNomOpaService service = newSetService();

        TreeMap<AttributeType, AttributeValue> big = new TreeMap<>(Comparator.comparing(AttributeType::getId));
        for (int i = 0; i <= SklNomOpaService.MAX_ITEMS; i++) {
            AttributeType type = saveType("ОБОЛЬШ" + i, AttributeValueType.STRING);
            big.put(type, saveValue(type, "Знач" + i));
        }

        assertThatThrownBy(() -> findOrCreateTx(service, nom, big))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("максимум");
    }

    // === Find-or-create ===

    @Test
    void lookupDoesNotCreate() {
        AttributeType color = saveType("ОЛУКАП", AttributeValueType.STRING);
        AttributeValue red = saveValue(color, "Красный");
        Nomenclature nom = saveNomenclature("N-LOOK");
        SklNomOpaService service = newSetService();

        Map<AttributeType, AttributeValue> attrs = Map.of(color, red);
        assertThat(service.lookup(nom, attrs)).isNull();
        assertThat(sklNomOpaRepository.findByNomenclature(nom)).isEmpty();
    }

    @Test
    void emptySetIsLazyAndReused() {
        Nomenclature nom = saveNomenclature("N-EMPTY");
        SklNomOpaService service = newSetService();

        SklNomOpa first = findOrCreateTx(service, nom, Map.of());
        assertThat(first.getCanonical()).isEmpty();
        assertThat(service.getItems(first)).isEmpty();

        SklNomOpa second = service.getOrCreateEmpty(nom);
        assertThat(second.getId()).isEqualTo(first.getId()); // переиспользование, не дубль
        assertThat(sklNomOpaRepository.findByNomenclature(nom)).hasSize(1);
    }

    @Test
    void sameCombinationReusesSameSet() {
        AttributeType color = saveType("ОПОВТ", AttributeValueType.STRING);
        AttributeValue red = saveValue(color, "Красный");
        Nomenclature nom = saveNomenclature("N-REUSE");
        SklNomOpaService service = newSetService();

        SklNomOpa first = findOrCreateTx(service, nom, Map.of(color, red));
        SklNomOpa second = findOrCreateTx(service, nom, Map.of(color, red));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(sklNomOpaRepository.findByNomenclature(nom)).hasSize(1);
    }

    @Test
    void differentCombinationCreatesDifferentSet() {
        AttributeType color = saveType("ОРАЗН", AttributeValueType.STRING);
        AttributeValue red = saveValue(color, "Красный");
        AttributeValue blue = saveValue(color, "Синий");
        Nomenclature nom = saveNomenclature("N-DIFF");
        SklNomOpaService service = newSetService();

        SklNomOpa redSet = findOrCreateTx(service, nom, Map.of(color, red));
        SklNomOpa blueSet = findOrCreateTx(service, nom, Map.of(color, blue));

        assertThat(blueSet.getId()).isNotEqualTo(redSet.getId());
        assertThat(sklNomOpaRepository.findByNomenclature(nom)).hasSize(2);
    }

    @Test
    void sameCombinationOnAnotherNomenclatureIsAnotherSet() {
        AttributeType color = saveType("ОДРУГ", AttributeValueType.STRING);
        AttributeValue red = saveValue(color, "Красный");
        Nomenclature nom1 = saveNomenclature("N-DRG1");
        Nomenclature nom2 = saveNomenclature("N-DRG2");
        SklNomOpaService service = newSetService();

        SklNomOpa s1 = findOrCreateTx(service, nom1, Map.of(color, red));
        SklNomOpa s2 = findOrCreateTx(service, nom2, Map.of(color, red));

        assertThat(s2.getId()).isNotEqualTo(s1.getId()); // уникальность в разрезе номенклатуры
    }

    @Test
    void itemsAreSortedByTypeId() {
        AttributeType a1 = saveType("ОСОРТ1", AttributeValueType.STRING);
        AttributeType a2 = saveType("ОСОРТ2", AttributeValueType.STRING);
        AttributeValue v1 = saveValue(a1, "В1");
        AttributeValue v2 = saveValue(a2, "В2");
        Nomenclature nom = saveNomenclature("N-SORT");
        SklNomOpaService service = newSetService();

        Map<AttributeType, AttributeValue> attrs = new HashMap<>();
        attrs.put(a2, v2);
        attrs.put(a1, v1);
        SklNomOpa set = findOrCreateTx(service, nom, attrs);

        List<SklNomOpaValue> items = service.getItems(set);
        assertThat(items).extracting(i -> i.getAttrType().getId())
            .containsExactly(a1.getId(), a2.getId()); // порядок канона, а не вставки
    }

    // === Гонка ===

    @Test
    void concurrentFindOrCreateYieldsSingleSet() throws Exception {
        AttributeType color = saveType("ОГОНКА", AttributeValueType.STRING);
        AttributeValue red = saveValue(color, "Красный");
        Nomenclature nom = saveNomenclature("N-RACE");
        SklNomOpaService service = newSetService();
        Map<AttributeType, AttributeValue> attrs = Map.of(color, red);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SklNomOpa> f1 = pool.submit(() -> {
                start.await();
                return findOrCreateTx(service, nom, attrs);
            });
            Future<SklNomOpa> f2 = pool.submit(() -> {
                start.await();
                return findOrCreateTx(service, nom, attrs);
            });
            start.countDown();

            SklNomOpa s1 = f1.get(30, TimeUnit.SECONDS);
            SklNomOpa s2 = f2.get(30, TimeUnit.SECONDS);

            assertThat(s1.getId()).isEqualTo(s2.getId());
            assertThat(sklNomOpaRepository.findByNomenclature(nom)).hasSize(1);
            assertThat(sklNomOpaValueRepository.findBySetOrderByAttrTypeId(s1)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // === Привязки (схема) ===

    @Test
    void bindingsAreReplacedAtomically() {
        AttributeType color = saveType("ОПРИВ1", AttributeValueType.STRING);
        AttributeType size = saveType("ОПРИВ2", AttributeValueType.STRING);
        Nomenclature nom = saveNomenclature("N-BIND1");
        NomSklAttributeService bindings = newBindingsService();

        bindings.setBindings(nom, List.of(
            new NomSklAttribute(nom, color, true),
            new NomSklAttribute(nom, size, false)));
        assertThat(bindings.findByNomenclature(nom)).hasSize(2);

        // замена: цвет отвязан, размер обязательный
        setBindingsTx(bindings, nom, List.of(new NomSklAttribute(nom, size, true)));
        List<NomSklAttribute> after = bindings.findByNomenclature(nom);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getAttrType().getId()).isEqualTo(size.getId());
        assertThat(after.get(0).isRequired()).isTrue();
    }

    @Test
    void bindingRejectsInactiveType() {
        AttributeType color = attributeTypeRepository.save(new AttributeType("ОПРИВ3", "Цвет", AttributeValueType.STRING));
        color.setActive(false);
        attributeTypeRepository.save(color);
        Nomenclature nom = saveNomenclature("N-BIND2");
        NomSklAttributeService bindings = newBindingsService();

        assertThatThrownBy(() -> bindings.setBindings(nom, List.of(new NomSklAttribute(nom, color, false))))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("неактивен");
    }

    @Test
    void unbindKeepsExistingSets() {
        AttributeType color = saveType("ОПРИВ4", AttributeValueType.STRING);
        AttributeValue red = saveValue(color, "Красный");
        Nomenclature nom = saveNomenclature("N-BIND3");
        SklNomOpaService sets = newSetService();
        NomSklAttributeService bindings = newBindingsService();

        bindings.bind(nom, color, true);
        SklNomOpa set = findOrCreateTx(sets, nom, Map.of(color, red));

        bindings.unbind(bindings.findBinding(nom, color).orElseThrow().getId());

        assertThat(bindings.findByNomenclature(nom)).isEmpty();          // схема изменена
        assertThat(sklNomOpaRepository.findById(set.getId())).isPresent(); // экземпляры живут
    }

    // === Переименование значения: канон стабилен, displayName пересобирается ===

    @Test
    void renameKeepsCanonicalAndRebuildsDisplayName() {
        AttributeType color = saveType("ОЦВЕТРЕН", AttributeValueType.STRING);
        AttributeType size = saveType("ОРАЗМРЕН", AttributeValueType.STRING);
        Nomenclature nom = saveNomenclature("N-REN1");
        AttributeValueService values = newValueService();
        AttributeValue red = values.getOrCreate(color, "Красный");
        AttributeValue m = values.getOrCreate(size, "M");
        SklNomOpaService sets = newSetService();

        Map<AttributeType, AttributeValue> attrs = Map.of(color, red, size, m);
        SklNomOpa set = findOrCreateTx(sets, nom, attrs);
        String canonicalBefore = set.getCanonical();

        values.renameValue(red.getId(), "Алый", null); // «Красный» — опечатка, исправление написания

        SklNomOpa reloaded = sklNomOpaRepository.findById(set.getId()).orElseThrow();
        assertThat(reloaded.getCanonical()).isEqualTo(canonicalBefore); // id стабилен → канон стабилен

        SklNomOpa again = findOrCreateTx(sets, nom, Map.of(color, red, size, m));
        assertThat(again.getId()).isEqualTo(set.getId()); // набор не «разъехался» на два

        List<SklNomOpaValue> items = sets.getItems(reloaded);
        assertThat(items).extracting(i -> i.getValue().getName())
            .contains("Алый"); // строки ссылаются по id и показывают исправленное имя
        assertThat(reloaded.getDisplayName()).contains("Алый"); // кэш пересобран той же транзакцией
    }

    // === Immutable ===

    @Test
    void setSaveCreateUpdateDeleteAreClosed() {
        Nomenclature nom = saveNomenclature("N-IMM");
        SklNomOpaService service = newSetService();
        SklNomOpa set = findOrCreateTx(service, nom, Map.of());

        SklNomOpa detached = new SklNomOpa(nom, "", "");
        detached.setId(set.getId());

        assertThatThrownBy(() -> service.save(detached))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> service.create(new SklNomOpa(nom, "", "")))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> service.update(detached))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> service.delete(set.getId()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // === Инфраструктура ===

    /** Вызов setBindings в явной транзакции (сервис без Spring-прокси, нужен remove). */
    private void setBindingsTx(NomSklAttributeService service, Nomenclature nomenclature,
                               List<NomSklAttribute> bindings) {
        new TransactionTemplate(transactionManager).execute(status -> {
            service.setBindings(nomenclature, bindings);
            return null;
        });
    }

    /**
     * C4.6 волна E: сервис привязок больше не наследует compatibility base — ему нужны только
     * repository (предметная замена набора) и canonical handle (стандартная поверхность).
     * В {@code @DataJpaTest}-срезе canonical pipeline недостижим, поэтому handle подменён
     * заглушкой, которая пишет теми же repository — проверяется поведение самих привязок.
     */
    private NomSklAttributeService newBindingsService() {
        org.ipro.data.CanonicalEntityService<NomSklAttribute> canonical =
            mock(org.ipro.data.CanonicalEntityService.class);
        when(canonical.save(any(NomSklAttribute.class)))
            .thenAnswer(invocation -> nomSklAttributeRepository.save(invocation.getArgument(0)));
        doAnswer(invocation -> {
            nomSklAttributeRepository.deleteById(invocation.getArgument(0));
            return null;
        }).when(canonical).delete(any(Long.class));
        return new NomSklAttributeService(nomSklAttributeRepository, canonical);
    }
}
