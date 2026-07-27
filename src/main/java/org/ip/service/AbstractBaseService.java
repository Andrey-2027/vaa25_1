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
import java.util.Map;
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
        Class<T> domainClass = getDomainClass();
        EntityGraph<T> graph = buildFetchGraph(domainClass);
        if (graph != null) {
            var hints = Map.of("jakarta.persistence.fetchgraph", (Object) graph);
            return Optional.ofNullable(entityManager.find(domainClass, id, hints));
        }
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
        return findAllWithFetchGraph(spec, pageable, null);
    }

    /**
     * Вариант с явными путями fetch-графа — для ListForm с динамическим составом колонок.
     * fetchPaths == null → граф строится из статических метаданных (как раньше);
     * непустая коллекция → граф строится ровно из переданных путей (с поддержкой вложенных
     * путей через subgraph, например "unitOfMeasurement.parentUnit").
     */
    @Override
    public Page<T> findAll(Specification<T> spec, Pageable pageable, java.util.Collection<String> fetchPaths) {
        return findAllWithFetchGraph(spec, pageable, fetchPaths);
    }

    protected Page<T> findAllWithFetchGraph(Specification<T> spec, Pageable pageable,
                                            java.util.Collection<String> fetchPaths) {
        Class<T> domainClass = getDomainClass();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<T> dataQuery = cb.createQuery(domainClass);
        Root<T> dataRoot = dataQuery.from(domainClass);
        dataQuery.select(dataRoot);
        applySpec(spec, dataRoot, dataQuery, cb);
        applySort(pageable, dataRoot, dataQuery, cb);

        TypedQuery<T> typedQuery = entityManager.createQuery(dataQuery);
        EntityGraph<T> graph = (fetchPaths != null)
            ? buildFetchGraph(domainClass, fetchPaths)
            : buildFetchGraph(domainClass);
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
        List<jakarta.persistence.criteria.Order> orders = new java.util.ArrayList<>();
        for (org.springframework.data.domain.Sort.Order order : pageable.getSort()) {
            for (jakarta.persistence.criteria.Path<?> path : sortPaths(root, order.getProperty())) {
                orders.add(order.isAscending() ? cb.asc(path) : cb.desc(path));
            }
        }
        query.orderBy(orders);
    }

    /**
     * Path'ы для ORDER BY по свойству сортировки (ключу колонки грида):
     *   - обычное поле — root.get;
     *   - путь через точку ("unitOfMeasurement.name") — через LEFT JOIN, а не неявный
     *     INNER JOIN: сортировка не должна выкидывать из списка строки с незаполненной ссылкой;
     *   - ссылочная колонка (сама или конечный сегмент пути — ENTITY_REFERENCE) — разворачивается
     *     в displaySortFields целевой сущности (SQL-эквивалент её displayName), т.е. одна колонка
     *     грида может дать несколько ORDER BY-выражений; без displaySortFields — по самой ссылке
     *     (Hibernate сортирует по её PK), как раньше.
     */
    private List<jakarta.persistence.criteria.Path<?>> sortPaths(Root<T> root, String property) {
        String[] segments = property.split("\\.");
        jakarta.persistence.criteria.From<?, ?> from = root;
        for (int i = 0; i < segments.length - 1; i++) {
            from = from.join(segments[i], jakarta.persistence.criteria.JoinType.LEFT);
        }
        String last = segments[segments.length - 1];

        List<String> displayFields = displaySortFieldsFor(property);
        if (!displayFields.isEmpty()) {
            jakarta.persistence.criteria.From<?, ?> target =
                from.join(last, jakarta.persistence.criteria.JoinType.LEFT);
            return displayFields.stream()
                .<jakarta.persistence.criteria.Path<?>>map(target::get)
                .toList();
        }
        return List.of(from.get(last));
    }

    /**
     * displaySortFields целевой сущности, если конечный сегмент пути — ссылка на
     * metadata-сущность с непустым displaySortFields; иначе пустой список (fallback
     * на сортировку по самой ссылке).
     */
    private List<String> displaySortFieldsFor(String property) {
        try {
            org.ip.metadata.ColumnPath columnPath =
                org.ip.metadata.ColumnPath.resolve(getDomainClass(), property);
            if (columnPath.getResolvedType() != FieldType.ENTITY_REFERENCE) {
                return List.of();
            }
            return metadataResolver.resolve(columnPath.getJavaType()).getDisplaySortFields();
        } catch (IllegalArgumentException invalidPathOrNoMetadata) {
            return List.of();
        }
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

    /**
     * EntityGraph из явного списка JPA-путей (в т.ч. вложенных через точку). Вложенный путь
     * "a.b" превращается в subgraph(a).addAttributeNodes(b). null — если список пуст.
     */
    private EntityGraph<T> buildFetchGraph(Class<T> domainClass, java.util.Collection<String> paths) {
        if (paths.isEmpty()) {
            return null;
        }
        EntityGraph<T> graph = entityManager.createEntityGraph(domainClass);
        for (String path : paths) {
            String[] segments = path.split("\\.");
            if (segments.length == 1) {
                graph.addAttributeNodes(segments[0]);
            } else {
                jakarta.persistence.Subgraph<?> subgraph = graph.addSubgraph(segments[0]);
                for (int i = 1; i < segments.length - 1; i++) {
                    subgraph = subgraph.addSubgraph(segments[i]);
                }
                subgraph.addAttributeNodes(segments[segments.length - 1]);
            }
        }
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
