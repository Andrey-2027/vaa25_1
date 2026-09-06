package org.ipro.crud.jpa;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.ipro.crud.BaseService;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.crud.ReferenceCheckService;
import org.ipro.crud.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Минимальная валидирующая CRUD-база платформы (план reportstudio-reverse-deps, 2.3):
 * bean-валидация + reference-check на удаление, поверх JpaRepository.
 *
 * <p>Сознательно НЕ включает (в отличие от {@code org.ipro.crud.AbstractBaseService}):
 * RLS write/read-policy и read-gate, нумерацию (@Numbered), metadata fetch-graphs,
 * UI-search по метаданным, конвенции имён бинов. Наследники добавляют своё
 * (см. ReportTemplateService). Идентификатор — Long ({@link IdentifiableEntity}).</p>
 */
@jakarta.transaction.Transactional
public class ValidatedJpaCrudService<T extends IdentifiableEntity> implements BaseService<T, Long> {

    protected final JpaRepository<T, Long> repository;
    private final Validator validator;
    private final ReferenceCheckService referenceCheckService;
    private final Class<T> domainClass;

    protected ValidatedJpaCrudService(JpaRepository<T, Long> repository, Validator validator,
                                      ReferenceCheckService referenceCheckService) {
        this.repository = repository;
        this.validator = validator;
        this.referenceCheckService = referenceCheckService;
        this.domainClass = resolveDomainClass();
    }

    @Override
    public T save(T entity) {
        validate(entity);
        return repository.save(entity);
    }

    @Override
    public T create(T entity) {
        return save(entity);
    }

    @Override
    public T update(T entity) {
        return save(entity);
    }

    /**
     * Удаление защищено проверкой ссылочной целостности: если на запись есть ссылки
     * из других сущностей, удаление блокируется ({@link ReferenceCheckService}).
     */
    @Override
    public void delete(Long id) {
        referenceCheckService.checkNoReferences(domainClass(), id);
        repository.deleteById(id);
    }

    @Override
    public Optional<T> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public List<T> findAll() {
        return repository.findAll();
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    /**
     * UI-search в базе не реализован (см. javadoc класса) — переопределяйте
     * предметным поиском.
     */
    @Override
    public List<T> search(String term) {
        throw new UnsupportedOperationException(
                "search(String) not implemented for " + getClass().getSimpleName());
    }

    @Override
    public Page<T> search(String term, Pageable pageable) {
        throw new UnsupportedOperationException(
                "search(String, Pageable) not implemented for " + getClass().getSimpleName());
    }

    /** Домен-класс дженерика — для reference-check удаления. */
    protected Class<T> domainClass() {
        return domainClass;
    }

    protected void validate(T entity) {
        Set<ConstraintViolation<T>> violations = validator.validate(entity);
        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (ConstraintViolation<T> violation : violations) {
                sb.append(violation.getPropertyPath())
                        .append(": ")
                        .append(violation.getMessage())
                        .append("\n");
            }
            throw new ValidationException(sb.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private Class<T> resolveDomainClass() {
        return (Class<T>) ((ParameterizedType) getClass().getGenericSuperclass())
                .getActualTypeArguments()[0];
    }
}
