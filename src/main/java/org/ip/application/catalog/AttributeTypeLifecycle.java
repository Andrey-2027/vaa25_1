package org.ip.application.catalog;

import org.ip.model.AttributeType;
import org.ip.model.AttributeValueType;
import org.ip.repository.AttributeTypeRepository;
import org.ip.repository.AttributeValueRepository;
import org.ipro.crud.ValidationException;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntitySaveContext;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Каноническая lifecycle-точка справочника «Тип атрибута».
 *
 * <p>Раньше эти правила жили в {@code AttributeTypeService.validateBusinessRules} — то есть
 * применялись только тем путём сохранения, которым шёл typed-сервис. Один typed handler
 * вместо хука внутри сервиса даёт то же правило всем каналам: generic form, aggregate
 * boundary, REST/import. Порядок не меняется: canonical write pipeline вызывает
 * {@link #beforeSave(EntitySaveContext)} после capability-границы и раннего RLS, до
 * persistence.</p>
 */
@Component
public class AttributeTypeLifecycle implements EntityLifecycle<AttributeType> {

    private final AttributeTypeRepository attributeTypeRepository;
    private final AttributeValueRepository attributeValueRepository;

    public AttributeTypeLifecycle(AttributeTypeRepository attributeTypeRepository,
                                  AttributeValueRepository attributeValueRepository) {
        this.attributeTypeRepository = Objects.requireNonNull(attributeTypeRepository,
            "attributeTypeRepository must not be null");
        this.attributeValueRepository = Objects.requireNonNull(attributeValueRepository,
            "attributeValueRepository must not be null");
    }

    @Override
    public Class<AttributeType> entityType() {
        return AttributeType.class;
    }

    @Override
    public void beforeSave(EntitySaveContext<AttributeType> context) {
        AttributeType entity = context.entity();
        requireDictionaryMatchesValueType(entity);
        requireValueTypeStableWhileValuesExist(entity);
    }

    /** «Словарь» заполняется ровно для типа значения «Ссылка». */
    private void requireDictionaryMatchesValueType(AttributeType entity) {
        boolean hasDictionary = entity.getTargetDictionary() != null
            && !entity.getTargetDictionary().isBlank();
        if (entity.getValueType() != AttributeValueType.REF && hasDictionary) {
            throw new ValidationException(
                "Поле «Словарь» заполняется только для типа значения «Ссылка» (у типа "
                    + entity.getCode() + " тип «" + entity.getValueType().getLabel() + "»).");
        }
        if (entity.getValueType() == AttributeValueType.REF && !hasDictionary) {
            throw new ValidationException(
                "Для типа значения «Ссылка» обязательно заполнить «Словарь» (у типа "
                    + entity.getCode() + ").");
        }
    }

    /**
     * Смена типа значения при существующих значениях запрещена: сохранённые строки
     * {@link org.ip.model.AttributeValue} интерпретируются по типу и стали бы нечитаемыми.
     */
    private void requireValueTypeStableWhileValuesExist(AttributeType entity) {
        if (entity.getId() == null) {
            return;
        }
        AttributeType current = attributeTypeRepository.findById(entity.getId()).orElse(null);
        if (current == null || current.getValueType() == entity.getValueType()) {
            return;
        }
        if (attributeValueRepository.existsByAttrType(current)) {
            throw new ValidationException(
                "Нельзя сменить тип значения с «" + current.getValueType().getLabel()
                    + "» на «" + entity.getValueType().getLabel()
                    + "»: у типа " + entity.getCode() + " уже есть значения. "
                    + "Заведите новый тип атрибута.");
        }
    }
}
