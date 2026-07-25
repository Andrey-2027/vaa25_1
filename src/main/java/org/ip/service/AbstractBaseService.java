package org.ip.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Transactional;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.FieldMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.metadata.annotation.FieldType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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

    @Autowired
    private ReferenceCheckService referenceCheckService;

    @Autowired
    private MetadataResolver metadataResolver;

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
        referenceCheckService.checkNoReferences(getDomainClass(), id);
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
        return findAllWithFetchGraph(spec, pageable);
    }

    /**
     * Универсальная реализация findAll(Specification, Pageable) с автоматическим
     * fetch-джойном entity-reference колонок грида через EntityGraph — вместо блока
     * `@ManyToOne(fetch = EAGER)` на самом мэппинге (см. обсуждение "LAZY + EntityGraph
     * по метаданным грида, а не блок EAGER на всём мэппинге").
     *
     * Строит запрос напрямую через EntityManager/Criteria API (а не через
     * JpaSpecificationExecutor.findAll()), потому что Spring Data не даёт способа
     * подмешать динамический EntityGraph в готовый findAll(Specification, Pageable) —
     * а нам нужен именно динамический граф, собранный из @FieldMetadata текущей
     * сущности, а не статический @EntityGraph на репозитории.
     *
     * EntityGraph строится ТОЛЬКО из полей типа ENTITY_REFERENCE, которые реально
     * показываются в гриде (EntityMetadataInfo.getGridFields()) — не более. Для
     * сущностей без @EntityMetadata (например, legacy Workshop) граф не строится,
     * запрос выполняется как обычный LAZY-запрос (без явного fetch join) — в этом
     * случае N+1 на рендере грида берёт на себя hibernate.default_batch_fetch_size
     * (см. application.properties).
     *
     * Публичные сервисы вызывают этот метод из своего findAll(Specification, Pageable)
     * вместо repository.findAll(spec, pageable) напрямую.
     */
    protected Page<T> findAllWithFetchGraph(Specification<T> spec, Pageable pageable) {
        Class<T> domainClass = getDomainClass();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<T> dataQuery = cb.createQuery(domainClass);
        Root<T> dataRoot = dataQuery.from(domainClass);
        dataQuery.select(dataRoot);
        applySpec(spec, dataRoot, dataQuery, cb);
        applySort(pageable, dataRoot, dataQuery, cb);

        TypedQuery<T> typedQuery = entityManager.createQuery(dataQuery);
        EntityGraph<T> graph = buildFetchGraph(domainClass);
        if (graph != null) {
            typedQuery.setHint("jakarta.persistence.fetchgraph", graph);
        }
        if (pageable.isPaged()) {
            typedQuery.setFirstResult((int) pageable.getOffset());
            typedQuery.setMaxResults(pageable.getPageSize());
        }
        List<T> content = typedQuery.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<T> countRoot = countQuery.from(domainClass);
        countQuery.select(cb.count(countRoot));
        applySpec(spec, countRoot, countQuery, cb);
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    private void applySpec(Specification<T> spec, Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        if (spec == null) return;
        Predicate predicate = spec.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }
    }

    private void applySort(Pageable pageable, Root<T> root, CriteriaQuery<T> query, CriteriaBuilder cb) {
        if (pageable.getSort().isUnsorted()) return;
        List<jakarta.persistence.criteria.Order> orders = pageable.getSort().stream()
            .map(order -> order.isAscending()
                ? cb.asc(root.get(order.getProperty()))
                : cb.desc(root.get(order.getProperty())))
            .toList();
        query.orderBy(orders);
    }

    /**
     * EntityGraph по ENTITY_REFERENCE-полям грида (из @EntityMetadata/@FieldMetadata) —
     * ровно то, что нужно для рендера грида, не более. null — если сущность не
     * metadata-driven, или у неё нет ни одной entity-reference колонки.
     */
    private EntityGraph<T> buildFetchGraph(Class<T> domainClass) {
        EntityMetadataInfo meta;
        try {
            meta = metadataResolver.resolve(domainClass);
        } catch (IllegalArgumentException notMetadataDriven) {
            return null;
        }
        List<String> refFields = meta.getGridFields().stream()
            .filter(f -> f.getResolvedType() == FieldType.ENTITY_REFERENCE)
            .map(FieldMetadataInfo::getName)
            .toList();
        if (refFields.isEmpty()) {
            return null;
        }
        EntityGraph<T> graph = entityManager.createEntityGraph(domainClass);
        refFields.forEach(graph::addAttributeNodes);
        return graph;
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
