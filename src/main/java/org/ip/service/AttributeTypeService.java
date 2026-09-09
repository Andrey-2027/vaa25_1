package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValueType;
import org.ip.repository.AttributeTypeRepository;
import org.ip.repository.AttributeValueRepository;
import org.ipro.crud.AbstractBaseService;
import org.ipro.crud.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AttributeTypeService extends AbstractBaseService<AttributeType, Long> {

    private final AttributeTypeRepository attributeTypeRepository;
    private final AttributeValueRepository attributeValueRepository;

    public AttributeTypeService(AttributeTypeRepository repository,
                                AttributeValueRepository attributeValueRepository,
                                Validator validator) {
        super(repository, validator);
        this.attributeTypeRepository = repository;
        this.attributeValueRepository = attributeValueRepository;
    }

    @Override
    protected void validateBusinessRules(AttributeType entity) {
        if (entity.getValueType() != AttributeValueType.REF
                && entity.getTargetDictionary() != null
                && !entity.getTargetDictionary().isBlank()) {
            throw new ValidationException(
                "Поле «Словарь» заполняется только для типа значения «Ссылка» (у типа "
                    + entity.getCode() + " тип «" + entity.getValueType().getLabel() + "»).");
        }
        if (entity.getValueType() == AttributeValueType.REF
                && (entity.getTargetDictionary() == null || entity.getTargetDictionary().isBlank())) {
            throw new ValidationException(
                "Для типа значения «Ссылка» обязательно заполнить «Словарь» (у типа " + entity.getCode() + ").");
        }
        if (entity.getId() != null) {
            AttributeType current = attributeTypeRepository.findById(entity.getId()).orElse(null);
            if (current != null && current.getValueType() != entity.getValueType()
                    && attributeValueRepository.existsByAttrType(current)) {
                throw new ValidationException(
                    "Нельзя сменить тип значения с «" + current.getValueType().getLabel()
                        + "» на «" + entity.getValueType().getLabel()
                        + "»: у типа " + entity.getCode() + " уже есть значения. "
                        + "Заведите новый тип атрибута.");
            }
        }
    }

    @Override
    public List<AttributeType> search(String term) {
        if (term == null || term.isEmpty()) {
            return findAll();
        }
        return attributeTypeRepository.searchByTerm(term, PageRequest.of(0, 100));
    }

    @Override
    public Page<AttributeType> findAll(Specification<AttributeType> spec, Pageable pageable) {
        return findAllWithFetchGraph(spec, pageable);
    }
}