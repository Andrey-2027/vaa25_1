package org.ip.service;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.model.GroupNom;
import org.ip.model.Nomenclature;
import org.ip.model.NomAttributeValue;
import org.ip.model.UnitOfMeasurement;
import org.ip.repository.AttributeTypeRepository;
import org.ip.repository.AttributeValueRepository;
import org.ip.repository.GroupNomRepository;
import org.ip.repository.NomenclatureRepository;
import org.ip.repository.SklNomOpaRepository;
import org.ip.repository.SklNomOpaValueRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ipro.crud.LookupService;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.crud.ValidationException;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ядро единого словаря значений атрибутов: find-or-create по типам, нормализация,
 * дедуп, гонка создания, бессмертность значений.
 *
 * <p>Все тесты без транзакции теста ({@code NOT_SUPPORTED}): значения создаются
 * сервисом в собственных REQUIRES_NEW-транзакциях и коммитятся — каждая проверка
 * работает со своим типом атрибута, поэтому остатки строк соседних тестов не мешают.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttributeValueServiceTest {

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private AttributeValueRepository attributeValueRepository;
    @Autowired
    private AttributeTypeRepository attributeTypeRepository;
    @Autowired
    private GroupNomRepository groupNomRepository;
    @Autowired
    private NomenclatureRepository nomenclatureRepository;
    @Autowired
    private UnitOfMeasurementRepository unitOfMeasurementRepository;
    @Autowired
    private SklNomOpaRepository sklNomOpaRepository;
    @Autowired
    private SklNomOpaValueRepository sklNomOpaValueRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private AttributeValueService newService() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        // Lookup через реальный EntityManager слайса (GroupNom незащищён RLS —
        // для RLS-пути есть отдельная интеграционная проверка полным контекстом).
        LookupService lookupService = mock(LookupService.class);
        when(lookupService.findById(any(Class.class), any())).thenAnswer(invocation -> {
            Class<?> entityClass = invocation.getArgument(0);
            Object id = invocation.getArgument(1);
            return Optional.ofNullable(entityManager.find(entityClass, id));
        });
        ManagedEntityCatalog entityCatalog =
            new ManagedEntityCatalog(entityManager.getEntityManagerFactory());
        AttributeValueService service = new AttributeValueService(
            attributeValueRepository, attributeTypeRepository,
            sklNomOpaRepository, sklNomOpaValueRepository,
            lookupService, entityCatalog, validator, transactionManager);
        ReflectionTestUtils.setField(service, "accessService", mock(AccessService.class));
        ReflectionTestUtils.setField(service, "numberingService", Optional.empty());
        RlsReadGate readGate = mock(RlsReadGate.class);
        when(readGate.canRead(any(), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "rlsReadGate", readGate);
        return service;
    }

    private AttributeType saveType(String code, AttributeValueType valueType) {
        AttributeType type = new AttributeType(code, code, valueType);
        if (valueType == AttributeValueType.REF) {
            type.setTargetDictionary(GroupNom.class.getName());
        }
        return attributeTypeRepository.save(type);
    }

    private Nomenclature saveNomenclature(String code) {
        UnitOfMeasurement unit =
            unitOfMeasurementRepository.save(new UnitOfMeasurement("шт" + code, "Штука " + code, "ШТ" + code));
        return nomenclatureRepository.save(new Nomenclature(code, code, unit));
    }

    /**
     * Строка owned-секции пишется и читается через EntityManager: у неё нет собственного
     * repository — он закрыт как параллельный вход в агрегат, а секция сохраняется только
     * через aggregate boundary владельца.
     */
    private void persistBinding(Nomenclature nomenclature, AttributeType type, AttributeValue value) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.persist(new NomAttributeValue(nomenclature, type, value)));
    }

    private NomAttributeValue bindingOf(Nomenclature nomenclature) {
        // join fetch: проверяется код словарного значения, а detached-строка после
        // закрытия транзакции ленивый proxy уже не инициализирует
        return new TransactionTemplate(transactionManager).execute(status ->
            entityManager.createQuery(
                    "select n from NomAttributeValue n join fetch n.attrValue where n.nomenclature = :nom",
                    NomAttributeValue.class)
                .setParameter("nom", nomenclature)
                .getSingleResult());
    }

    // === STRING ===

    @Test
    void stringDedupIsCaseInsensitiveAndTrims() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-STR", AttributeValueType.STRING);

        AttributeValue first = service.getOrCreate(type, " Красный ");
        AttributeValue second = service.getOrCreate(type, "красный");

        assertThat(first.getCode()).isEqualTo("Красный"); // первый вариант сохраняется
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(attributeValueRepository.findByAttrTypeAndCodeUp(type, "КРАСНЫЙ")).isPresent();
    }

    @Test
    void stringLookupDoesNotCreate() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-STR2", AttributeValueType.STRING);

        assertThat(service.lookup(type, "Несуществующее")).isEmpty();
        assertThat(attributeValueRepository.findByAttrTypeAndCodeUp(type, "НЕСУЩЕСТВУЮЩЕЕ")).isEmpty();
    }

    @Test
    void stringEmptyValueRejected() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-STR3", AttributeValueType.STRING);

        assertThatThrownBy(() -> service.getOrCreate(type, "   "))
            .isInstanceOf(ValidationException.class);
    }

    // === NUMBER ===

    @Test
    void numberCanonicalFormDedupsCommaAndTrailingZeros() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-NUM", AttributeValueType.NUMBER);

        AttributeValue a = service.getOrCreate(type, AttributeValueService.parseNumber("1,5"));
        AttributeValue b = service.getOrCreate(type, new BigDecimal("1.50"));
        AttributeValue c = service.getOrCreate(type, new BigDecimal("1.5"));

        assertThat(a.getCode()).isEqualTo("1.5");
        assertThat(b.getId()).isEqualTo(a.getId());
        assertThat(c.getId()).isEqualTo(a.getId());
    }

    @Test
    void numberDifferentValuesAreDifferentRows() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-NUM2", AttributeValueType.NUMBER);

        AttributeValue a = service.getOrCreate(type, new BigDecimal("1.5"));
        AttributeValue b = service.getOrCreate(type, new BigDecimal("2.5"));

        assertThat(b.getId()).isNotEqualTo(a.getId());
    }

    @Test
    void numberParseRejectsGarbage() {
        assertThatThrownBy(() -> AttributeValueService.parseNumber("полтора"))
            .isInstanceOf(ValidationException.class);
    }

    // === REF ===

    @Test
    void refDedupsByRefIdNotByName() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-REF", AttributeValueType.REF);

        GroupNom first = groupNomRepository.save(new GroupNom("G-1", "Иванов И.И."));
        GroupNom second = groupNomRepository.save(new GroupNom("G-2", "Иванов И.И."));

        AttributeValue v1 = service.getOrCreateRef(type, first.getId());
        AttributeValue v2 = service.getOrCreateRef(type, second.getId());
        AttributeValue again = service.getOrCreateRef(type, first.getId());

        assertThat(v1.getRefId()).isEqualTo(first.getId());
        assertThat(v2.getRefId()).isEqualTo(second.getId());
        assertThat(v2.getId()).isNotEqualTo(v1.getId()); // одинаковые имена — разные строки
        assertThat(again.getId()).isEqualTo(v1.getId());  // тот же refId — та же строка
        assertThat(v1.getCodeUp()).isNull();              // REF: дедуп по refId, не по имени
        assertThat(v1.getName()).isEqualTo("G-1 Иванов И.И."); // снапшот displayName
    }

    @Test
    void refMissingRowRejected() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-REF2", AttributeValueType.REF);

        assertThatThrownBy(() -> service.getOrCreateRef(type, 999999L))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void refUsesDictionaryConfiguredOnAttributeType() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-REF-NOM", AttributeValueType.REF);
        type.setTargetDictionary(Nomenclature.class.getName());
        attributeTypeRepository.save(type);
        Nomenclature nomenclature = saveNomenclature("RN");

        assertThat(service.resolveTargetDictionary(type)).isEqualTo(Nomenclature.class);
        assertThat(service.getOrCreateRef(type, nomenclature.getId()).getRefId())
            .isEqualTo(nomenclature.getId());
    }

    // === ENUM ===

    @Test
    void enumValueWithCodeAndName() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-ENUM", AttributeValueType.ENUM);

        AttributeValue red = service.createEnumValue(type, "RED", "Красный");

        assertThat(red.getCode()).isEqualTo("RED");
        assertThat(red.getName()).isEqualTo("Красный");
        // дедуп по коду без учёта регистра: getOrCreate находит существующее
        AttributeValue found = service.getOrCreate(type, "red");
        assertThat(found.getId()).isEqualTo(red.getId());
    }

    @Test
    void enumEmptyCodeDefaultsToName() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-ENUM2", AttributeValueType.ENUM);

        AttributeValue value = service.createEnumValue(type, " ", "Синий");

        assertThat(value.getCode()).isEqualTo("Синий");
        assertThat(value.getName()).isEqualTo("Синий");
    }

    // === Гонка ===

    @Test
    void concurrentCreateOfSameValueYieldsSingleRow() throws Exception {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-RACE", AttributeValueType.STRING);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AttributeValue> f1 = pool.submit(() -> {
                start.await();
                return service.getOrCreate(type, "Красный");
            });
            Future<AttributeValue> f2 = pool.submit(() -> {
                start.await();
                return service.getOrCreate(type, "красный");
            });
            start.countDown();

            AttributeValue v1 = f1.get(30, TimeUnit.SECONDS);
            AttributeValue v2 = f2.get(30, TimeUnit.SECONDS);

            assertThat(v1.getId()).isEqualTo(v2.getId());
            assertThat(attributeValueRepository.findByAttrTypeAndCodeUp(type, "КРАСНЫЙ")).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    // === Обработка переименования ===

    @Test
    void renameUpdatesCodeNameAndCodeUpKeepingId() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-REN1", AttributeValueType.STRING);
        AttributeValue value = service.getOrCreate(type, "Красный");

        AttributeValue renamed = service.renameValue(value.getId(), "Алый", null);

        assertThat(renamed.getId()).isEqualTo(value.getId()); // id не меняется — привязки следуют
        assertThat(renamed.getCode()).isEqualTo("Алый");
        assertThat(renamed.getName()).isEqualTo("Алый");      // скаляр: имя следует за кодом
        assertThat(renamed.getCodeUp()).isEqualTo("АЛЫЙ");
        assertThat(attributeValueRepository.findByAttrTypeAndCodeUp(type, "КРАСНЫЙ")).isEmpty();
    }

    @Test
    void renameNumberNormalizesCanonicalForm() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-REN-NUM", AttributeValueType.NUMBER);
        AttributeValue value = service.getOrCreate(type, AttributeValueService.parseNumber("1,5"));

        AttributeValue renamed = service.renameValue(value.getId(), "1,50", null);

        assertThat(renamed.getCode()).isEqualTo("1.5"); // «1,50» → канон «1.5»
        assertThat(renamed.getName()).isEqualTo("1.5");
    }

    @Test
    void renameEnumKeepsLabelUnlessNewOneProvided() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-REN-ENUM", AttributeValueType.ENUM);
        AttributeValue value = service.createEnumValue(type, "RED", "Красный");

        AttributeValue codeOnly = service.renameValue(value.getId(), "CRIMSON", null);
        assertThat(codeOnly.getCode()).isEqualTo("CRIMSON");
        assertThat(codeOnly.getName()).isEqualTo("Красный"); // подпись сохраняется

        AttributeValue withLabel = service.renameValue(value.getId(), "BURGUNDY", "Бордо");
        assertThat(withLabel.getCode()).isEqualTo("BURGUNDY");
        assertThat(withLabel.getName()).isEqualTo("Бордо");
    }

    @Test
    void renameRefForbidden() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-REN-REF", AttributeValueType.REF);
        GroupNom group = groupNomRepository.save(new GroupNom("G-REN", "Иванов И.И."));
        AttributeValue value = service.getOrCreateRef(type, group.getId());

        assertThatThrownBy(() -> service.renameValue(value.getId(), "Петров", null))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Ссылка");
    }

    @Test
    void renameToOccupiedSlotRejectedAsMerge() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-REN2", AttributeValueType.STRING);
        AttributeValue red = service.getOrCreate(type, "Красный");
        service.getOrCreate(type, "Синий");

        assertThatThrownBy(() -> service.renameValue(red.getId(), "Синий", null))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("слияние");
        // исходная строка не тронута
        assertThat(attributeValueRepository.findById(red.getId()).orElseThrow().getCode())
            .isEqualTo("Красный");
    }

    @Test
    void renameToSameCodeIsNoop() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-REN3", AttributeValueType.STRING);
        AttributeValue value = service.getOrCreate(type, "Красный");

        AttributeValue renamed = service.renameValue(value.getId(), " Красный ", null); // trim

        assertThat(renamed.getId()).isEqualTo(value.getId());
        assertThat(renamed.getCode()).isEqualTo("Красный");
    }

    @Test
    void renameBlankCodeRejected() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-REN4", AttributeValueType.STRING);
        AttributeValue value = service.getOrCreate(type, "Красный");

        assertThatThrownBy(() -> service.renameValue(value.getId(), "   ", null))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void renameFreesOldSlotForNewValue() {
        // Зафиксированное допущение: после «красный»→«алый» слот «красный» свободен и
        // может быть занят новым значением. Перед КСУ-наборами закрывается «могилкой».
        AttributeValueService service = newService();
        AttributeType type = saveType("T-REN5", AttributeValueType.STRING);
        AttributeValue value = service.getOrCreate(type, "Красный");

        service.renameValue(value.getId(), "Алый", null);
        AttributeValue oldCodeAgain = service.getOrCreate(type, "Красный");

        assertThat(oldCodeAgain.getId()).isNotEqualTo(value.getId());
    }

    @Test
    void renameReflectsInBindingsByReference() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-REN-BIND", AttributeValueType.STRING);
        AttributeValue value = service.getOrCreate(type, "Красный");
        Nomenclature nomenclature = saveNomenclature("N-BIND");
        persistBinding(nomenclature, type, value);

        service.renameValue(value.getId(), "Алый", null);

        // привязка ссылается по ID и показывает новый код без каких-либо UPDATE по привязкам
        NomAttributeValue binding = bindingOf(nomenclature);
        assertThat(binding.getAttrValue().getId()).isEqualTo(value.getId());
        assertThat(binding.getAttrValue().getCode()).isEqualTo("Алый");
    }

    // === Бессмертность и ограничения ===

    @Test
    void deleteIsNotProvided() {
        AttributeValueService service = newService();
        AttributeType type = saveType("T-DEL", AttributeValueType.STRING);
        AttributeValue value = service.getOrCreate(type, "Красный");

        assertThatThrownBy(() -> service.delete(value.getId()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void directUpdateIsNotProvided() {
        // Единственный способ изменить значение — обработка renameValue
        AttributeValueService service = newService();
        AttributeType type = saveType("T-UPD", AttributeValueType.STRING);
        AttributeValue value = service.getOrCreate(type, "Красный");

        AttributeValue detached = new AttributeValue(type, "Алый", "Алый", "АЛЫЙ", null);
        detached.setId(value.getId());

        assertThatThrownBy(() -> service.update(detached))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void operationRejectedForWrongValueType() {
        AttributeValueService service = newService();
        AttributeType stringType = saveType("T-OP1", AttributeValueType.STRING);
        AttributeType refType = saveType("T-OP2", AttributeValueType.REF);

        assertThatThrownBy(() -> service.getOrCreate(stringType, new BigDecimal("1.5")))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.getOrCreate(refType, "Красный"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.createEnumValue(stringType, "A", "B"))
            .isInstanceOf(ValidationException.class);
    }
}
