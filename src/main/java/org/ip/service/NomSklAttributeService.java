package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.AttributeType;
import org.ip.model.Nomenclature;
import org.ip.model.NomSklAttribute;
import org.ip.repository.NomSklAttributeRepository;
import org.ipro.crud.AbstractBaseService;
import org.ipro.crud.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Привязки «Номенклатура ↔ Атрибут КСУ» — схема разреза карточки позиции.
 *
 * <p>Это настройка (а не история): допустимы замена всего списка привязок позиции
 * ({@link #setBindings}) и удаление привязки — уже созданные наборы {@link org.ip.model.SklNomOpa}
 * продолжают ссылаться на свои строки и не затрагиваются.
 */
@Service
public class NomSklAttributeService extends AbstractBaseService<NomSklAttribute, Long> {

    private final NomSklAttributeRepository nomSklAttributeRepository;

    public NomSklAttributeService(NomSklAttributeRepository repository, Validator validator) {
        super(repository, validator);
        this.nomSklAttributeRepository = repository;
    }

    /**
     * Заменить список привязок позиции одной транзакцией (delete old + insert new).
     * Пустой список — убрать все привязки (движения существующими наборами продолжают работать).
     */
    public void setBindings(Nomenclature nomenclature, List<NomSklAttribute> bindings) {
        if (nomenclature == null || nomenclature.getId() == null) {
            throw new ValidationException("Для сохранения привязок атрибутов КСУ позиция должна быть сохранена.");
        }
        List<NomSklAttribute> rows = bindings == null ? List.of() : bindings;
        for (NomSklAttribute row : rows) {
            if (row.getAttrType() == null) {
                throw new ValidationException("Атрибут КСУ не может быть пустым.");
            }
            if (!row.getAttrType().isActive()) {
                throw new ValidationException(
                    "Тип атрибута «" + row.getAttrType().getDisplayName() + "» неактивен — привязать нельзя.");
            }
        }
        nomSklAttributeRepository.deleteByNomenclature(nomenclature);
        // Hibernate выполняет INSERT до DELETE в очереди действий: без flush вставка той же
        // пары (номенклатура, тип) упала бы на уникальном ограничении ещё живой строки.
        nomSklAttributeRepository.flush();
        for (NomSklAttribute row : rows) {
            nomSklAttributeRepository.save(new NomSklAttribute(nomenclature, row.getAttrType(), row.isRequired()));
        }
    }

    /** Привязки позиции (для формы документа: ровно эти типы доступны для ввода значений). */
    public List<NomSklAttribute> findByNomenclature(Nomenclature nomenclature) {
        return nomSklAttributeRepository.findByNomenclature(nomenclature);
    }

    /** Привязка конкретного типа (проверка «тип привязан к позиции»). */
    public java.util.Optional<NomSklAttribute> findBinding(Nomenclature nomenclature, AttributeType attrType) {
        return nomSklAttributeRepository.findByNomenclatureAndAttrType(nomenclature, attrType);
    }

    /**
     * Привязать тип к позиции (запись сразу, как в AttributeTypeForm — без отложенного сохранения):
     * тип обязан быть активен и ещё не привязан.
     */
    public NomSklAttribute bind(Nomenclature nomenclature, AttributeType attrType, boolean required) {
        if (nomenclature == null || nomenclature.getId() == null) {
            throw new ValidationException("Привязка атрибута КСУ сохраняется только для сохранённой позиции.");
        }
        if (attrType == null || attrType.getId() == null) {
            throw new ValidationException("Атрибут КСУ не может быть пустым.");
        }
        if (!attrType.isActive()) {
            throw new ValidationException(
                "Тип атрибута «" + attrType.getDisplayName() + "» неактивен — привязать нельзя.");
        }
        if (nomSklAttributeRepository.findByNomenclatureAndAttrType(nomenclature, attrType).isPresent()) {
            throw new ValidationException(
                "Тип атрибута «" + attrType.getDisplayName() + "» уже привязан к позиции «"
                    + nomenclature.getDisplayName() + "».");
        }
        return save(new NomSklAttribute(nomenclature, attrType, required));
    }

    /** Убрать привязку (настройка, не история: существующие наборы не затрагиваются). */
    public void unbind(Long bindingId) {
        nomSklAttributeRepository.findById(bindingId).ifPresent(row ->
            nomSklAttributeRepository.delete(row));
    }

    @Override
    protected void validateBusinessRules(NomSklAttribute entity) {
        if (entity.getNomenclature() != null && entity.getNomenclature().getId() == null) {
            throw new ValidationException("Привязка атрибута КСУ сохраняется только вместе с сохранённой позицией.");
        }
    }

    @Override
    public Page<NomSklAttribute> findAll(Specification<NomSklAttribute> spec, Pageable pageable) {
        return findAllWithFetchGraph(spec, pageable);
    }
}
