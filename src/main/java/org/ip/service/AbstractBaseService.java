package org.ip.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Transactional;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;

import org.ipro.crud.IdentifiableEntity;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Transactional
public abstract class AbstractBaseService<T extends IdentifiableEntity, ID> implements BaseService<T, ID> {

    protected final JpaRepository<T, ID> repository;
    protected final Validator validator;

    @PersistenceContext
    private EntityManager entityManager;

    protected AbstractBaseService(JpaRepository<T, ID> repository, Validator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    @Override
    public T save(T entity) {
        validate(entity);
        return repository.save(entity);
    }

    @Override
    public T create(T entity) {
        validate(entity);
        return repository.save(entity);
    }

    @Override
    public T update(T entity) {
        validate(entity);
        return repository.save(entity);
    }

    @Override
    public void delete(ID id) {
        repository.deleteById(id);
    }

    @Override
    public Optional<T> findById(ID id) {
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

    @Override
    public Page<T> search(String term, Pageable pageable) {
        throw new UnsupportedOperationException(
                "search(String, Pageable) not implemented for " + getClass().getSimpleName());
    }

    @Override
    public Page<T> findAll(Specification<T> spec, Pageable pageable) {
        throw new UnsupportedOperationException(
                "findAll(Specification, Pageable) not implemented for " + getClass().getSimpleName());
    }

    public Number sum(String fieldName, Specification<T> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object> query = cb.createQuery();
        Root<T> root = query.from(getDomainClass());
        query.select(cb.sum(root.get(fieldName)));
        if (spec != null) {
            query.where(spec.toPredicate(root, query, cb));
        }
        Object result = entityManager.createQuery(query).getSingleResult();
        return result != null ? (Number) result : 0;
    }

    @SuppressWarnings("unchecked")
    private Class<T> getDomainClass() {
        return (Class<T>) ((ParameterizedType) getClass().getGenericSuperclass())
                .getActualTypeArguments()[0];
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
}
