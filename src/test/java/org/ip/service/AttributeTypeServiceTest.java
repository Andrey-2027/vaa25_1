package org.ip.service;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.repository.AttributeTypeRepository;
import org.ip.repository.AttributeValueRepository;
import org.ipro.crud.ValidationException;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Справочник «Тип атрибута»: правила заполнения «Словаря» (только для «Ссылки»)
 * и запрет смены типа значения при существующих значениях.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttributeTypeServiceTest {

    @Autowired
    private AttributeTypeRepository attributeTypeRepository;
    @Autowired
    private AttributeValueRepository attributeValueRepository;

    private AttributeTypeService newService() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        AttributeTypeService service = new AttributeTypeService(
            attributeTypeRepository, attributeValueRepository, validator);
        ReflectionTestUtils.setField(service, "accessService", mock(AccessService.class));
        ReflectionTestUtils.setField(service, "numberingService", Optional.empty());
        RlsReadGate readGate = mock(RlsReadGate.class);
        when(readGate.canRead(any(), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "rlsReadGate", readGate);
        return service;
    }

    @Test
    void targetDictionaryAllowedOnlyForRef() {
        AttributeTypeService service = newService();

        AttributeType stringType = new AttributeType("T-1", "Строка", AttributeValueType.STRING);
        stringType.setTargetDictionary("org.ip.model.GroupNom");
        assertThatThrownBy(() -> service.save(stringType))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("только для типа значения «Ссылка»");

        AttributeType refType = new AttributeType("T-2", "Ссылка", AttributeValueType.REF);
        assertThatThrownBy(() -> service.save(refType))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("обязательно заполнить «Словарь»");

        refType.setTargetDictionary("org.ip.model.GroupNom");
        AttributeType saved = service.save(refType);
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void valueTypeChangeBlockedWhenValuesExist() {
        AttributeTypeService service = newService();
        AttributeType type = service.save(
            new AttributeType("T-3", "Цвет", AttributeValueType.STRING));
        attributeValueRepository.save(
            new AttributeValue(type, "Красный", "Красный", "КРАСНЫЙ", null));

        type.setValueType(AttributeValueType.ENUM);
        assertThatThrownBy(() -> service.update(type))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("уже есть значения");
    }

    @Test
    void valueTypeChangeAllowedWhenNoValues() {
        AttributeTypeService service = newService();
        AttributeType type = service.save(
            new AttributeType("T-4", "Цвет", AttributeValueType.STRING));

        type.setValueType(AttributeValueType.ENUM);
        AttributeType updated = service.update(type);

        assertThat(updated.getValueType()).isEqualTo(AttributeValueType.ENUM);
    }
}