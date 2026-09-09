package org.ip.service;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.model.Nomenclature;
import org.ip.model.NomAttributeValue;
import org.ip.model.UnitOfMeasurement;
import org.ip.repository.AttributeTypeRepository;
import org.ip.repository.AttributeValueRepository;
import org.ip.repository.NomenclatureRepository;
import org.ip.repository.NomAttributeValueRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ipro.crud.ReferenceCheckService;
import org.ipro.crud.ValidationException;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Значения атрибутов номенклатуры: замена значений позиции одной транзакцией,
 * одно значение на тип, словарная целостность «тип ↔ значение».
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NomAttributeValueServiceTest {

    @Autowired
    private NomAttributeValueRepository nomAttributeValueRepository;
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

    private NomAttributeValueService newService() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        NomAttributeValueService service =
            new NomAttributeValueService(nomAttributeValueRepository, validator);
        ReflectionTestUtils.setField(service, "accessService", mock(AccessService.class));
        ReflectionTestUtils.setField(service, "numberingService", Optional.empty());
        ReflectionTestUtils.setField(service, "referenceCheckService", mock(ReferenceCheckService.class));
        ReflectionTestUtils.setField(service, "rlsFilterActivator", mock(RlsFilterActivator.class));
        MetadataResolver metadataResolver = mock(MetadataResolver.class);
        when(metadataResolver.resolve(any())).thenThrow(new IllegalArgumentException("no metadata"));
        ReflectionTestUtils.setField(service, "metadataResolver", metadataResolver);
        RlsReadGate readGate = mock(RlsReadGate.class);
        when(readGate.canRead(any(), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "rlsReadGate", readGate);
        return service;
    }

    private Nomenclature saveNomenclature(String code) {
        // коды единицы уникальны на тест: NOT_SUPPORTED коммитит строки, общие коды пересекались бы
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

    /**
     * Вручную сконструированный сервис не имеет Spring-прокси: class-level @Transactional
     * не действует — оборачиваем вызовы в явную транзакцию (тест сам NOT_SUPPORTED).
     */
    private void setValuesTx(NomAttributeValueService service, Nomenclature nomenclature,
                             Map<AttributeType, AttributeValue> values) {
        new TransactionTemplate(transactionManager).execute(status -> {
            service.setValues(nomenclature, values);
            return null;
        });
    }

    @Test
    void setValuesReplacesPreviousValues() {
        NomAttributeValueService service = newService();
        Nomenclature nomenclature = saveNomenclature("N-1");
        AttributeType color = saveType("ЦВЕТ", AttributeValueType.STRING);
        AttributeType size = saveType("РАЗМЕР", AttributeValueType.STRING);
        AttributeValue red = saveValue(color, "Красный");
        AttributeValue blue = saveValue(color, "Синий");
        AttributeValue m = saveValue(size, "M");

        setValuesTx(service, nomenclature, Map.of(color, red, size, m));
        assertThat(service.getValues(nomenclature)).containsEntry(color, red);

        // замена: цвет меняется, размер остаётся
        setValuesTx(service, nomenclature, Map.of(color, blue, size, m));
        Map<AttributeType, AttributeValue> after = service.getValues(nomenclature);
        assertThat(after).containsEntry(color, blue);
        assertThat(after).containsEntry(size, m);
        assertThat(after).hasSize(2);
    }

    @Test
    void setValuesEmptyClearsAll() {
        NomAttributeValueService service = newService();
        Nomenclature nomenclature = saveNomenclature("N-2");
        AttributeType color = saveType("ЦВЕТ2", AttributeValueType.STRING);
        AttributeValue red = saveValue(color, "Красный");

        setValuesTx(service, nomenclature, Map.of(color, red));
        setValuesTx(service, nomenclature, Map.of());

        assertThat(service.getValues(nomenclature)).isEmpty();
    }

    @Test
    void setValuesRejectsValueFromAnotherType() {
        NomAttributeValueService service = newService();
        Nomenclature nomenclature = saveNomenclature("N-3");
        AttributeType color = saveType("ЦВЕТ3", AttributeValueType.STRING);
        AttributeType size = saveType("РАЗМЕР3", AttributeValueType.STRING);
        AttributeValue m = saveValue(size, "M");

        assertThatThrownBy(() -> setValuesTx(service, nomenclature, Map.of(color, m)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("не принадлежит типу");
    }

    @Test
    void saveRejectsCrossTypePair() {
        NomAttributeValueService service = newService();
        Nomenclature nomenclature = saveNomenclature("N-4");
        AttributeType color = saveType("ЦВЕТ4", AttributeValueType.STRING);
        AttributeType size = saveType("РАЗМЕР4", AttributeValueType.STRING);
        AttributeValue m = saveValue(size, "M");

        assertThatThrownBy(() -> service.save(
                new NomAttributeValue(nomenclature, color, m)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("не принадлежит типу");
    }

    @Test
    void oneValuePerTypeIsEnforcedByUniqueConstraint() {
        NomAttributeValueService service = newService();
        Nomenclature nomenclature = saveNomenclature("N-5");
        AttributeType color = saveType("ЦВЕТ5", AttributeValueType.STRING);
        AttributeValue red = saveValue(color, "Красный");
        AttributeValue blue = saveValue(color, "Синий");

        service.save(new NomAttributeValue(nomenclature, color, red));
        assertThatThrownBy(() -> nomAttributeValueRepository.saveAndFlush(
                new NomAttributeValue(nomenclature, color, blue)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteBindingKeepsDictionaryValue() {
        NomAttributeValueService service = newService();
        Nomenclature nomenclature = saveNomenclature("N-6");
        AttributeType color = saveType("ЦВЕТ6", AttributeValueType.STRING);
        AttributeValue red = saveValue(color, "Красный");

        setValuesTx(service, nomenclature, Map.of(color, red));
        NomAttributeValue binding = nomAttributeValueRepository.findByNomenclatureOrderByAttrType(nomenclature).get(0);

        service.delete(binding.getId());

        assertThat(nomAttributeValueRepository.findByNomenclatureOrderByAttrType(nomenclature)).isEmpty();
        assertThat(attributeValueRepository.findById(red.getId())).isPresent();
    }

    @Test
    void getValuesIsTypedLinkedMap() {
        NomAttributeValueService service = newService();
        Nomenclature nomenclature = saveNomenclature("N-7");
        AttributeType color = saveType("ЦВЕТ7", AttributeValueType.STRING);
        AttributeValue red = saveValue(color, "Красный");

        setValuesTx(service, nomenclature, Map.of(color, red));
        Map<AttributeType, AttributeValue> values = service.getValues(nomenclature);

        assertThat(values).isInstanceOf(LinkedHashMap.class);
        assertThat(values.keySet()).extracting(AttributeType::getId).containsExactly(color.getId());
    }
}