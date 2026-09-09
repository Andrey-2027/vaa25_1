package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.Nomenclature;
import org.ip.model.NomAttributeValue;
import org.ip.repository.NomAttributeValueRepository;
import org.ipro.crud.AbstractBaseService;
import org.ipro.crud.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Значения атрибутов номенклатуры (описание позиции): «позиция → тип → значение».
 *
 * <p>Одно значение на тип у позиции (unique {@code (nomenclature, attrType)}).
 * Замена значений позиции — {@link #setValues}: удалить старые привязки и вставить новые
 * в одной транзакции; строки значений остаются в едином словаре {@link AttributeValue}.
 */
@Service
public class NomAttributeValueService extends AbstractBaseService<NomAttributeValue, Long> {

    private final NomAttributeValueRepository nomAttributeValueRepository;

    public NomAttributeValueService(NomAttributeValueRepository repository, Validator validator) {
        super(repository, validator);
        this.nomAttributeValueRepository = repository;
    }

    /**
     * Заменить значения атрибутов позиции: одна транзакция (delete old + insert new).
     * Пустая карта — очистить все значения позиции.
     */
    public void setValues(Nomenclature nomenclature, Map<AttributeType, AttributeValue> values) {
        if (nomenclature == null || nomenclature.getId() == null) {
            throw new ValidationException("Для сохранения значений атрибутов позиция должна быть сохранена.");
        }
        Map<AttributeType, AttributeValue> normalized = values == null ? Map.of() : values;
        for (Map.Entry<AttributeType, AttributeValue> entry : normalized.entrySet()) {
            AttributeType type = entry.getKey();
            AttributeValue value = entry.getValue();
            if (type == null || value == null) {
                throw new ValidationException("Тип и значение атрибута не могут быть пустыми.");
            }
            if (value.getAttrType() == null || !value.getAttrType().getId().equals(type.getId())) {
                throw new ValidationException(
                    "Значение «" + value.getDisplayName() + "» не принадлежит типу «"
                        + type.getDisplayName() + "»: у позиции «" + nomenclature.getDisplayName()
                        + "» — исправьте связку (тип, значение).");
            }
        }
        nomAttributeValueRepository.deleteByNomenclature(nomenclature);
        // Hibernate выполняет INSERT до DELETE в очереди действий: без flush вставка той же
        // пары (номенклатура, тип) упала бы на уникальном ограничении ещё живой строки.
        nomAttributeValueRepository.flush();
        for (Map.Entry<AttributeType, AttributeValue> entry : normalized.entrySet()) {
            nomAttributeValueRepository.save(
                new NomAttributeValue(nomenclature, entry.getKey(), entry.getValue()));
        }
    }

    /** Значения атрибутов позиции: тип → значение (порядок — по типу). */
    public Map<AttributeType, AttributeValue> getValues(Nomenclature nomenclature) {
        Map<AttributeType, AttributeValue> result = new LinkedHashMap<>();
        for (NomAttributeValue row : nomAttributeValueRepository.findByNomenclatureOrderByAttrType(nomenclature)) {
            result.put(row.getAttrType(), row.getAttrValue());
        }
        return result;
    }

    /** Строки привязок позиции (для списков и отчётов). */
    public List<NomAttributeValue> findByNomenclature(Nomenclature nomenclature) {
        return nomAttributeValueRepository.findByNomenclatureOrderByAttrType(nomenclature);
    }

    @Override
    protected void validateBusinessRules(NomAttributeValue entity) {
        if (entity.getAttrValue() != null && entity.getAttrType() != null
                && (entity.getAttrValue().getAttrType() == null
                    || !entity.getAttrValue().getAttrType().getId().equals(entity.getAttrType().getId()))) {
            throw new ValidationException(
                "Значение «" + entity.getAttrValue().getDisplayName() + "» не принадлежит типу «"
                    + entity.getAttrType().getDisplayName() + "».");
        }
    }

    @Override
    public List<NomAttributeValue> search(String term) {
        if (term == null || term.isEmpty()) {
            return findAll();
        }
        return nomAttributeValueRepository.searchByTerm(term, PageRequest.of(0, 100));
    }

    @Override
    public Page<NomAttributeValue> findAll(Specification<NomAttributeValue> spec, Pageable pageable) {
        return findAllWithFetchGraph(spec, pageable);
    }
}