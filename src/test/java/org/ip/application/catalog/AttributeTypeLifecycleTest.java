package org.ip.application.catalog;

import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.repository.AttributeTypeRepository;
import org.ip.repository.AttributeValueRepository;
import org.ipro.crud.ValidationException;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.EntitySaveContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Правила справочника «Тип атрибута»: «Словарь» только для типа значения «Ссылка»
 * и запрет смены типа значения при существующих значениях.
 *
 * <p>C4.6 волна E: правила переехали из {@code AttributeTypeService.validateBusinessRules}
 * в канонический {@link AttributeTypeLifecycle}, который вызывается canonical write
 * pipeline после capability-границы и раннего RLS. Тест держит правило на том же уровне,
 * на котором его теперь применяет платформа.</p>
 *
 * <p>Транзакция теста отключена намеренно: правило смены типа значения сравнивает
 * сохраняемое состояние с текущим состоянием БД. В одной транзакции JPA вернул бы тот же
 * managed-инстанс, и правило проверяло бы объект сам с собой.</p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttributeTypeLifecycleTest {

    @Autowired
    private AttributeTypeRepository attributeTypeRepository;

    @Autowired
    private AttributeValueRepository attributeValueRepository;

    private AttributeTypeLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = new AttributeTypeLifecycle(attributeTypeRepository, attributeValueRepository);
    }

    @Test
    void targetDictionaryAllowedOnlyForRef() {
        AttributeType stringType = new AttributeType("T-1", "Строка", AttributeValueType.STRING);
        stringType.setTargetDictionary("org.ip.model.GroupNom");
        assertThatThrownBy(() -> lifecycle.beforeSave(saving(stringType)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("только для типа значения «Ссылка»");

        AttributeType refType = new AttributeType("T-2", "Ссылка", AttributeValueType.REF);
        assertThatThrownBy(() -> lifecycle.beforeSave(saving(refType)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("обязательно заполнить «Словарь»");

        refType.setTargetDictionary("org.ip.model.GroupNom");
        assertThatCode(() -> lifecycle.beforeSave(saving(refType))).doesNotThrowAnyException();
        assertThat(attributeTypeRepository.save(refType).getId()).isNotNull();
    }

    @Test
    void valueTypeChangeBlockedWhenValuesExist() {
        AttributeType type = attributeTypeRepository.save(
            new AttributeType("T-3", "Цвет", AttributeValueType.STRING));
        attributeValueRepository.save(
            new AttributeValue(type, "Красный", "Красный", "КРАСНЫЙ", null));

        type.setValueType(AttributeValueType.ENUM);
        assertThatThrownBy(() -> lifecycle.beforeSave(saving(type)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("уже есть значения");
    }

    @Test
    void valueTypeChangeAllowedWhenNoValues() {
        AttributeType type = attributeTypeRepository.save(
            new AttributeType("T-4", "Цвет", AttributeValueType.STRING));

        type.setValueType(AttributeValueType.ENUM);
        assertThatCode(() -> lifecycle.beforeSave(saving(type))).doesNotThrowAnyException();
    }

    @Test
    void newTypeHasNoCurrentStateToCompareWith() {
        AttributeType type = new AttributeType("T-5", "Ссылка", AttributeValueType.REF);
        type.setTargetDictionary("org.ip.model.GroupNom");

        assertThatCode(() -> lifecycle.beforeSave(saving(type))).doesNotThrowAnyException();
    }

    private static EntitySaveContext<AttributeType> saving(AttributeType entity) {
        return new EntitySaveContext<>(entity, EventContext.forEntity(
            AttributeType.class, entity.getId(), EventSource.SYSTEM, "save:AttributeType"));
    }
}
