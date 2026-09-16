package org.ip.service;

import org.ip.model.AttributeType;
import org.ip.model.Nomenclature;
import org.ip.model.NomSklAttribute;
import org.ip.repository.NomSklAttributeRepository;
import org.ipro.crud.BaseService;
import org.ipro.crud.ValidationException;
import org.ipro.data.CanonicalEntityService;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.telemetry.api.Measured;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Привязки «Номенклатура ↔ Атрибут КСУ» — схема разреза карточки позиции.
 *
 * <p>Это настройка (а не история): допустимы замена всего списка привязок позиции
 * ({@link #setBindings}) и удаление привязки — уже созданные наборы {@link org.ip.model.SklNomOpa}
 * продолжают ссылаться на свои строки и не затрагиваются.</p>
 *
 * <p>C4.6 волна E: класс больше не наследует compatibility base. Стандартная поверхность
 * ({@code findAll/findById/search/save/...}) делегируется canonical handle, поэтому список,
 * карточка и поиск типа идут теми же FetchPlan, RLS и capability границами, что и у типов
 * без собственного сервиса. Предметные операции над привязками остаются здесь, потому что
 * заменяют набор строк одной операцией, а не сохраняют одну сущность.</p>
 */
@Measured
@Service
public class NomSklAttributeService implements BaseService<NomSklAttribute, Long> {

    private final NomSklAttributeRepository nomSklAttributeRepository;
    private final CanonicalEntityService<NomSklAttribute> canonical;

    @Autowired
    public NomSklAttributeService(NomSklAttributeRepository nomSklAttributeRepository,
                                  EntityDataAccessResolver dataAccessResolver) {
        this.nomSklAttributeRepository = Objects.requireNonNull(nomSklAttributeRepository,
            "nomSklAttributeRepository must not be null");
        Objects.requireNonNull(dataAccessResolver, "dataAccessResolver must not be null");
        BaseService<NomSklAttribute, Long> handle = dataAccessResolver
            .<NomSklAttribute, Long>findService(NomSklAttribute.class)
            .orElseThrow(() -> new IllegalStateException(
                "NomSklAttribute не имеет canonical data handle: "
                    + dataAccessResolver.resolutionReason(NomSklAttribute.class)));
        @SuppressWarnings("unchecked")
        CanonicalEntityService<NomSklAttribute> resolved = (CanonicalEntityService<NomSklAttribute>) handle;
        this.canonical = resolved;
    }

    /**
     * Сборка с явным canonical handle — для тестов, где полный контекст не нужен.
     * Намеренно package-private, чтобы Spring autowiring видел ровно одного кандидата.
     */
    NomSklAttributeService(NomSklAttributeRepository nomSklAttributeRepository,
                           CanonicalEntityService<NomSklAttribute> canonical) {
        this.nomSklAttributeRepository = Objects.requireNonNull(nomSklAttributeRepository,
            "nomSklAttributeRepository must not be null");
        this.canonical = Objects.requireNonNull(canonical, "canonical must not be null");
    }

    // === Предметные операции над привязками ===

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
    public Optional<NomSklAttribute> findBinding(Nomenclature nomenclature, AttributeType attrType) {
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
        return canonical.save(new NomSklAttribute(nomenclature, attrType, required));
    }

    /** Убрать привязку (настройка, не история: существующие наборы не затрагиваются). */
    public void unbind(Long bindingId) {
        if (bindingId == null) {
            return;
        }
        nomSklAttributeRepository.findById(bindingId).ifPresent(row -> canonical.delete(row.getId()));
    }

    // === Стандартная поверхность: делегируется canonical handle ===

    @Override
    public NomSklAttribute save(NomSklAttribute entity) {
        return canonical.save(entity);
    }

    @Override
    public NomSklAttribute create(NomSklAttribute entity) {
        return canonical.create(entity);
    }

    @Override
    public NomSklAttribute update(NomSklAttribute entity) {
        return canonical.update(entity);
    }

    @Override
    public void delete(Long id) {
        canonical.delete(id);
    }

    @Override
    public Optional<NomSklAttribute> findById(Long id) {
        return canonical.findById(id);
    }

    @Override
    public List<NomSklAttribute> findAll() {
        return canonical.findAll();
    }

    @Override
    public Page<NomSklAttribute> findAll(Pageable pageable) {
        return canonical.findAll(pageable);
    }

    @Override
    public Page<NomSklAttribute> findAll(Specification<NomSklAttribute> spec, Pageable pageable) {
        return canonical.findAll(spec, pageable);
    }

    @Override
    public Page<NomSklAttribute> findAll(Specification<NomSklAttribute> spec, Pageable pageable,
                                         Collection<String> fetchPaths) {
        return canonical.findAll(spec, pageable, fetchPaths);
    }

    @Override
    public Page<NomSklAttribute> findAllByScenario(FetchScenario scenario,
                                                   Specification<NomSklAttribute> spec,
                                                   Pageable pageable,
                                                   Collection<String> additionalFetchPaths) {
        return canonical.findAllByScenario(scenario, spec, pageable, additionalFetchPaths);
    }

    @Override
    public List<NomSklAttribute> search(String term) {
        return canonical.search(term);
    }

    @Override
    public Page<NomSklAttribute> search(String term, Pageable pageable) {
        return canonical.search(term, pageable);
    }

    @Override
    public Number sum(String fieldName, Specification<NomSklAttribute> spec) {
        return canonical.sum(fieldName, spec);
    }
}
