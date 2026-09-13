package org.ipro.crud;

import org.ipro.crud.BaseEntity;
import org.ipro.crud.BaseService;
import org.ipro.crud.ReferenceCheckService;
import org.ipro.crud.ValidationException;

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
import org.ipro.fetch.instance.InstanceNameBridge;
import org.ipro.metadata.FetchGraphs;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.security.CurrentUser;
import org.ipro.numbering.NumberingService;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;

import org.ipro.crud.IdentifiableEntity;
import org.ipro.data.CanonicalReadExecutor;
import org.ipro.data.DetailRead;
import org.ipro.data.ListRead;
import org.ipro.data.PageRead;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.LinkedHashSet;
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

    @Autowired
    private RlsFilterActivator rlsFilterActivator;

    @Autowired
    private RlsReadGate rlsReadGate;

    @Autowired(required = false)
    private RlsPolicyEnforcer rlsPolicyEnforcer;

    @Autowired
    private Optional<NumberingService> numberingService;

    /**
     * Optional-бин контура entity events (см. {@code org.ipro.events}). Сервисы остаются
     * работоспособными и без него (юнит-тесты собирают сервис вручную), в приложении бин
     * приходит из {@code EventsAutoConfiguration}.
     */
    @Autowired
    private Optional<EntityEventPublisher> entityEventPublisher = Optional.empty();

    /**
     * Optional typed application lifecycle. Hand-built services remain usable without
     * the platform registry; Spring application context supplies it automatically.
     */
    @Autowired
    private Optional<EntityLifecycleRegistry> entityLifecycleRegistry = Optional.empty();

    /**
     * Optional for hand-built unit tests; in the application it removes metadata-declared
     * owned sections as part of the same root delete transaction.
     */
    @Autowired
    private Optional<GenericOwnedSectionService> genericOwnedSectionService = Optional.empty();

    /**
     * Планы загрузки C3.4 (см. ADR-0006): сценарий {@code LIST} для запроса списка,
     * {@code DETAIL} — для загрузки формы элемента.
     *
     * <p>В приложении бин присутствует всегда. При ручной сборке сервиса вне Spring
     * (юнит-тесты) поле остаётся null — тогда граф не строится, и запрос идёт обычным
     * LAZY-путём, как для сущности без metadata. Поэтому null здесь не маскируется
     * fallback-реализацией второго способа расчёта путей.</p>
     */
    @Autowired(required = false)
    private org.ipro.fetch.plan.FetchPlanRegistry fetchPlanRegistry;

    /**
     * Canonical read boundary C4.1 (см. ADR-0007 §4). Optional: вручную собранные
     * unit-тесты и частичные контексты без C4-границы продолжают работать по прежнему
     * пути, а стандартные чтения полного контекста идут через единый executor, а не
     * строят собственную security/fetch границу.
     */
    @Autowired(required = false)
    private CanonicalReadExecutor readExecutor;

    protected AbstractBaseService(JpaRepository<T, ID> repository, Validator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    @Override
    public T save(T entity) {
        authorizeWrite(entity, false);
        normalizeVersion(entity);
        assignNumbers(entity);
        validate(entity);
        Optional<T> original = originalForUpdate(entity);
        EntityEventPublisher.EventScope eventScope = openEntityEventOperation(entity);
        try {
            publishSaving(entity);
            publishUpdating(original, entity);
            validateBusinessRules(entity);
            T saved = repository.save(entity);
            publishSaved(saved);
            return saved;
        } finally {
            closeEventScope(eventScope);
        }
    }

    @Override
    public T create(T entity) {
        authorizeWrite(entity, false);
        normalizeVersion(entity);
        assignNumbers(entity);
        validate(entity);
        Optional<T> original = originalForUpdate(entity);
        EntityEventPublisher.EventScope eventScope = openEntityEventOperation(entity);
        try {
            publishSaving(entity);
            publishUpdating(original, entity);
            validateBusinessRules(entity);
            T saved = repository.save(entity);
            publishSaved(saved);
            return saved;
        } finally {
            closeEventScope(eventScope);
        }
    }

    @Override
    public T update(T entity) {
        authorizeWrite(entity, false);
        normalizeVersion(entity);
        validate(entity);
        Optional<T> original = originalForUpdate(entity);
        EntityEventPublisher.EventScope eventScope = openEntityEventOperation(entity);
        try {
            publishSaving(entity);
            publishUpdating(original, entity);
            validateBusinessRules(entity);
            T saved = repository.save(entity);
            publishSaved(saved);
            return saved;
        } finally {
            closeEventScope(eventScope);
        }
    }

    /**
     * Допускает сохранение сущностей, загруженных из строк, созданных до появления
     * {@code @Version} (колонка version была добавлена ddl-auto=update уже после
     * заполнения таблиц — см. backfill: update ... set version = 0 where version is null).
     * У такой строки version = null при id != null, и Hibernate в isTransient() видит
     * противоречие «версия говорит „новая", id говорит „сохранённая"» и бросает
     * PropertyValueException «Detached entity ... uninitialized version value».
     * Нормализуем null → 0 (эквивалент «запись никогда не изменялась»). Новые сущности
     * (id == null) не трогаем — version им присвоит Hibernate при вставке.
     */
    private void normalizeVersion(T entity) {
        if (entity instanceof BaseEntity base && base.getId() != null && base.getVersion() == null) {
            base.setVersion(0L);
        }
    }

    @Override
    public void delete(ID id) {
        Optional<T> existing = findById(id);
        if (existing.isEmpty()) {
            // For a protected type "not found" may mean "filtered out". Never turn that
            // into deleteById, because dimension values would be unavailable for enforcement.
            if (rlsPolicyEnforcer != null && rlsPolicyEnforcer.isProtected(getDomainClass())) {
                return;
            }
            referenceCheckService.checkNoReferences(getDomainClass(), id);
            repository.deleteById(id);
            return;
        }

        T entity = existing.get();
        // Ранний write-гейт: решение об удалении принимается до publishDeleting и до
        // удаления owned sections, иначе строки табличных частей уходили бы в БД до
        // проверки права (отменялись бы только rollback'ом).
        authorizeWrite(entity, true);
        EntityEventPublisher.EventScope eventScope = openEntityEventOperation(entity);
        try {
            publishDeleting(entity);
            genericOwnedSectionService.ifPresent(service ->
                service.deleteAllOwnedSections(entity));
            // Owned references have now been removed and flushed. Remaining references
            // are external blockers; an exception rolls the whole transaction back.
            referenceCheckService.checkNoReferences(getDomainClass(), id);
            repository.delete(entity);
            publishDeleted(entity);
        } finally {
            closeEventScope(eventScope);
        }
    }

    /**
     * Ранняя авторизация write-операции на границе сервиса (C3.7 hardening).
     *
     * <p>Вызывается первым действием create/update/delete/save, до нормализации,
     * bean-валидации, {@code validateBusinessRules}, lifecycle hooks и before-событий:
     * запрещённая операция не должна раскрывать результаты бизнес-валидации и не должна
     * давать пользовательскому расширителю платформы шанс выполнить внешний побочный
     * эффект, который откат транзакции не отменит.</p>
     *
     * <p>Решение принимает тот же {@link RlsPolicyEnforcer}, что и
     * {@code RlsRepositoryEnforcementAspect}: ранний гейт не выводит политику заново, а
     * лишь вызывает её раньше, поэтому не может разойтись с repository-границей.
     * Повторный вызов не ослабляет enforcement — {@code RlsWriteAuthorization.grant}
     * только перезаписывает capability равным значением, а потребляет её flush-listener
     * ровно один раз. Aspect и {@code RlsWriteEnforcementListener} остаются последним
     * рубежом для прямых вызовов repository и commit-time dirty checking.</p>
     *
     * <p>null-энфорсер — ручная сборка сервиса в юнит-тестах: тогда гейт отсутствует,
     * как и раньше.</p>
     */
    private void authorizeWrite(T entity, boolean delete) {
        if (rlsPolicyEnforcer == null || entity == null) {
            return;
        }
        if (delete) {
            rlsPolicyEnforcer.requireDelete(entity);
        } else {
            rlsPolicyEnforcer.requireUpdate(entity);
        }
    }

    @Override
    public Optional<T> findById(ID id) {
        if (id == null) {
            // Та же конвенция, что у LookupService.findById: отсутствующий id — «не найдено»,
            // а не NPE из canonical-запроса.
            return Optional.empty();
        }
        if (readExecutor != null) {
            return readExecutor.readDetail(DetailRead.of(getDomainClass(), id));
        }
        if (!canRead()) {
            return Optional.empty();
        }
        rlsFilterActivator.ensureRlsEnabled(entityManager);
        Class<T> domainClass = getDomainClass();
        EntityGraph<T> graph = buildFetchGraph(domainClass,
            org.ipro.fetch.plan.FetchScenario.DETAIL);
        if (graph != null) {
            var hints = Map.of("jakarta.persistence.fetchgraph", (Object) graph);
            return Optional.ofNullable(entityManager.find(domainClass, id, hints));
        }
        return repository.findById(id);
    }

    @Override
    public List<T> findAll() {
        if (readExecutor != null) {
            return readExecutor.readAll(ListRead.of(getDomainClass(),
                org.ipro.fetch.plan.FetchScenario.LIST));
        }
        if (!canRead()) {
            return List.of();
        }
        rlsFilterActivator.ensureRlsEnabled(entityManager);
        Class<T> domainClass = getDomainClass();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(domainClass);
        Root<T> root = query.from(domainClass);
        query.select(root);
        TypedQuery<T> typedQuery = entityManager.createQuery(query);
        EntityGraph<T> graph = buildFetchGraph(domainClass,
            org.ipro.fetch.plan.FetchScenario.LIST);
        if (graph != null) {
            typedQuery.setHint("jakarta.persistence.fetchgraph", graph);
        }
        return typedQuery.getResultList();
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        return findAllWithFetchGraph(null, pageable);
    }

    @Override
    public List<T> search(String term) {
        return search(term, org.ipro.data.SearchRead.defaultPage()).getContent();
    }

    /**
     * C4.4 (ADR-0007 §7): standard {@code search} — compatibility-делегат canonical
     * read boundary, а не {@code UnsupportedOperationException}. Терм трактуется
     * литерально, поля выводятся из единой лестницы (InstanceName → metadata-колонки),
     * порядок детерминирован, paging ограничен.
     *
     * <p>Типизированный сервис со специальной search-семантикой (special joins, full-text,
     * ranking) продолжает переопределять методы; default engine — для стандартных типов.
     * Hand-built unit-тесты без C4-границы получают явную диагностику, а не мгновенный
     * {@code UnsupportedOperationException} из середины базового класса.</p>
     */
    @Override
    public Page<T> search(String term, Pageable pageable) {
        return searchWithFields(term, pageable);
    }

    /**
     * Canonical search с явно выбранными полями для typed поиска, который сохраняет
     * предметную поверхность колонок, но использует ту же RLS/fetch/paging границу и
     * семантику для list и paged overloads.
     */
    protected Page<T> searchWithFields(String term, Pageable pageable, String... fields) {
        if (readExecutor == null) {
            throw new IllegalStateException("search требует canonical read boundary C4"
                + " (CanonicalReadExecutor): " + getClass().getSimpleName()
                + " собран вне Spring-контекста.");
        }
        return readExecutor.readSearch(new org.ipro.data.SearchRead<>(getDomainClass(),
            org.ipro.data.SearchContext.LIST, term, List.of(fields),
            pageable == null ? org.ipro.data.SearchRead.defaultPage() : pageable, List.of()));
    }

    @Override
    public Page<T> findAll(Specification<T> spec, Pageable pageable) {
        return findAllByScenario(org.ipro.fetch.plan.FetchScenario.LIST, spec, pageable, List.of());
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
     * Базовый граф берётся из сценария {@code LIST}; дополнительные пути могут расширить
     * его для динамического вида. Если C3 registry отсутствует, учитываются только явные
     * дополнительные пути, а при их отсутствии запрос идёт обычным JPA read-path.
     *
     * Публичные сервисы вызывают этот метод из своего findAll(Specification, Pageable)
     * вместо repository.findAll(spec, pageable) напрямую.
     */
    protected Page<T> findAllWithFetchGraph(Specification<T> spec, Pageable pageable) {
        return findAllWithFetchGraph(spec, pageable, null);
    }

    /**
     * Вариант с дополнительными путями fetch-графа — для динамического состава колонок.
     * Переданные пути добавляются к сценарию {@code LIST}; они не отключают его.
     */
    @Override
    public Page<T> findAll(Specification<T> spec, Pageable pageable, java.util.Collection<String> fetchPaths) {
        return findAllByScenario(org.ipro.fetch.plan.FetchScenario.LIST, spec, pageable, fetchPaths);
    }

    @Override
    public Page<T> findAllByScenario(org.ipro.fetch.plan.FetchScenario scenario,
                                     Specification<T> spec, Pageable pageable,
                                     java.util.Collection<String> additionalFetchPaths) {
        if (readExecutor != null) {
            return readExecutor.readPage(new PageRead<>(getDomainClass(), scenario, spec,
                pageable, additionalFetchPaths));
        }
        return findAllWithFetchGraph(spec, pageable, scenario, additionalFetchPaths);
    }

    protected Page<T> findAllWithFetchGraph(Specification<T> spec, Pageable pageable,
                                            java.util.Collection<String> fetchPaths) {
        return findAllByScenario(org.ipro.fetch.plan.FetchScenario.LIST, spec, pageable, fetchPaths);
    }

    protected Page<T> findAllWithFetchGraph(Specification<T> spec, Pageable pageable,
                                            org.ipro.fetch.plan.FetchScenario scenario,
                                            java.util.Collection<String> additionalFetchPaths) {
        if (!canRead()) {
            return Page.empty(pageable);
        }
        rlsFilterActivator.ensureRlsEnabled(entityManager);
        Class<T> domainClass = getDomainClass();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<T> dataQuery = cb.createQuery(domainClass);
        Root<T> dataRoot = dataQuery.from(domainClass);
        dataQuery.select(dataRoot);
        applySpec(spec, dataRoot, dataQuery, cb);
        applySort(pageable, dataRoot, dataQuery, cb);

        TypedQuery<T> typedQuery = entityManager.createQuery(dataQuery);
        EntityGraph<T> graph = buildFetchGraph(domainClass, scenario, additionalFetchPaths);
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
            org.ipro.metadata.ColumnPath columnPath =
                org.ipro.metadata.ColumnPath.resolve(getDomainClass(), property);
            if (columnPath.getResolvedType() != FieldType.ENTITY_REFERENCE) {
                return List.of();
            }
            return metadataResolver.resolve(columnPath.getJavaType()).getDisplaySortFields();
        } catch (IllegalArgumentException invalidPathOrNoMetadata) {
            return List.of();
        }
    }

    /**
     * EntityGraph сценария из единого плана C3.4 — вместо локального перечисления
     * ENTITY_REFERENCE-полей. План выводится в {@code FetchPlanRegistry} из эффективной
     * metadata, зависимостей instance name и объявленных зависимостей выбора, поэтому
     * список и форма грузят то, что объявлено, а не то, что знает этот класс.
     *
     * <p>null — если C3-граница не подключена (ручная сборка сервиса) и дополнительных
     * путей нет, либо если итоговый план пуст.</p>
     */
    private EntityGraph<T> buildFetchGraph(Class<T> domainClass,
                                           org.ipro.fetch.plan.FetchScenario scenario) {
        return buildFetchGraph(domainClass, scenario, List.of());
    }

    /** Сценарий — обязательная основа графа; dynamic paths могут только расширить её. */
    private EntityGraph<T> buildFetchGraph(Class<T> domainClass,
                                           org.ipro.fetch.plan.FetchScenario scenario,
                                           java.util.Collection<String> additionalPaths) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (fetchPlanRegistry == null) {
            if (additionalPaths == null || additionalPaths.isEmpty()) {
                return null;
            }
        } else {
            paths.addAll(fetchPlanRegistry.paths(domainClass, scenario));
        }
        if (additionalPaths != null) {
            paths.addAll(additionalPaths);
        }
        return buildFetchGraph(domainClass, paths);
    }

    /**
     * EntityGraph из явного списка JPA-путей (в т.ч. вложенных через точку). Вложенный путь
     * "a.b" превращается в subgraph(a).addAttributeNodes(b). Пути дополнительно углубляются
     * через {@link FetchGraphs#deepen} — чтобы getDisplayName() ссылочных целей не падал на
     * неинициализированных прокси после закрытия сессии. null — если список пуст.
     */
    private EntityGraph<T> buildFetchGraph(Class<T> domainClass, java.util.Collection<String> paths) {
        return FetchGraphs.fromPaths(entityManager, domainClass,
            FetchGraphs.deepen(domainClass, paths, metadataResolver,
                InstanceNameBridge::instanceNamePaths));
    }

    public Number sum(String fieldName, Specification<T> spec) {
        if (readExecutor != null) {
            return readExecutor.readSum(getDomainClass(), fieldName, spec);
        }
        if (!canRead()) {
            return 0;
        }
        rlsFilterActivator.ensureRlsEnabled(entityManager);
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

    /**
     * Строгий read-гейт CHECK_ONLY (Фаза 5 RLS-плана): класс с CHECK_ONLY-измерением
     * без гранта на чтение — пустые findById/findAll, даже если строки проходят по
     * построчным FILTERABLE-измерениям. Решение — единый {@link RlsReadGate}.
     */
    private boolean canRead() {
        if (rlsPolicyEnforcer != null) {
            return rlsPolicyEnforcer.prepareRead(getDomainClass(), entityManager);
        }
        return rlsReadGate.canRead(getDomainClass(), CurrentUser.username());
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

    /**
     * Хук доменных бизнес-правил сервиса: вызывается между {@link #validate(Object)}
     * (bean-валидация) и записью через repository в save/create/update; RLS write
     * enforcement при этом выполнен РАНЬШЕ — на входе в операцию
     * ({@link #authorizeWrite(Object, boolean)}), поэтому хук не запускается для
     * запрещённой операции. Repository-aspect и flush-listener остаются последним
     * рубежом для каналов в обход сервиса. По умолчанию
     * ничего не делает — переопределяется в сервисах с кросс-полевой/кросс-сущностной
     * логикой, которую bean-валидацией не выразить. Исключение из хука прерывает
     * сохранение, как и из validate().
     *
     * <p>Для новых правил приоритетный путь — типизированный
     * {@code EntityLifecycle.beforeSave} (см. {@link #publishSaving(Object)}): он
     * применяется на любом пути сохранения, а не только там, где правило было
     * прописано. Хук остаётся для совместимости и переводится в lifecycle handlers
     * по одному правилу за раз.</p>
     */
    protected void validateBusinessRules(T entity) {
        // no-op по умолчанию — правила есть только у конкретных сервисов
    }

    /**
     * Veto-capable entity-событие перед persistence — единая точка, в которой доменное
     * правило становится применимым к любому пути сохранения, а не к одному конкретному
     * экрану или use case.
     *
     * <p>Source = {@link EventSource#SYSTEM}: сам сервис не знает вызывающий канал
     * (UI/REST/импорт) — агрегатные операции публикуют свой {@code AggregateSavingEvent}
     * из use case, где источник известен явно. Событие публикуется до {@code repository.save},
     * внутри текущей транзакции: исключение listener'а отменяет сохранение.</p>
     */
    protected void publishSaving(T entity) {
        EventContext context = lifecycleContext(entity, "save:" + getDomainClass().getSimpleName());
        entityEventPublisher.ifPresent(publisher -> publisher.publishSaving(entity, context));
        entityLifecycleRegistry.ifPresent(registry -> registry.beforeSave(
            getDomainClass(), entity, context));
    }

    /** Вызвать typed callback только для существующей записи с доступным исходным состоянием. */
    protected void publishUpdating(Optional<T> original, T updated) {
        if (original.isEmpty()) {
            return;
        }
        EventContext context = lifecycleContext(updated,
            "update:" + getDomainClass().getSimpleName());
        entityLifecycleRegistry.ifPresent(registry -> registry.beforeUpdate(
            getDomainClass(), original.get(), updated, context));
    }

    /**
     * Публикует Saved внутри текущей транзакции и планирует Changed после commit.
     * Если сервис вызывается из typed aggregate use case, корневые события принадлежат
     * use case: сервис оставляет только EntitySavingEvent и не создаёт дубликаты.
     * В aggregate scope {@code onSave} также вызывается coordinator'ом после
     * persistence всех подключённых sections.
     */
    protected void publishSaved(T entity) {
        EventContext context = lifecycleContext(entity, "save:" + getDomainClass().getSimpleName());
        entityEventPublisher.ifPresent(publisher -> {
            if (publisher.isAggregateOperation(getDomainClass())) {
                return;
            }
            publisher.publishSaved(entity, context);
            publisher.publishChanged(entity, context);
        });
        entityLifecycleRegistry.ifPresent(registry -> {
            if (entityEventPublisher.isEmpty()
                || !entityEventPublisher.get().isAggregateOperation(getDomainClass())) {
                registry.onSave(getDomainClass(), entity, context);
            }
        });
    }

    /** Синхронный veto перед удалением. */
    protected void publishDeleting(T entity) {
        EventContext context = lifecycleContext(entity, "delete:" + getDomainClass().getSimpleName());
        entityEventPublisher.ifPresent(publisher -> publisher.publishDeleting(entity, context));
        entityLifecycleRegistry.ifPresent(registry -> registry.beforeDelete(
            getDomainClass(), entity, context));
    }

    /** Планирует факт удаления после успешного commit. */
    protected void publishDeleted(T entity) {
        entityEventPublisher.ifPresent(publisher -> publisher.publishDeleted(entity,
            publisher.contextFor(getDomainClass(), entity.getId(), EventSource.SYSTEM,
                "delete:" + getDomainClass().getSimpleName())));
    }

    /** Контекст для typed lifecycle callbacks с сохранением aggregate scope. */
    private EventContext lifecycleContext(T entity, String operationName) {
        return entityEventPublisher
            .map(publisher -> publisher.contextFor(
                getDomainClass(), entity.getId(), EventSource.SYSTEM, operationName))
            .orElseGet(() -> EventContext.forEntity(
                getDomainClass(), entity.getId(), EventSource.SYSTEM, operationName));
    }

    /**
     * Загрузить состояние до изменения. Detached payload получает отдельный managed
     * снимок; если вызывающий передал уже managed instance, JPA мог потерять старые
     * значения до входа в сервис, поэтому callback получает тот же instance в роли
     * original и updated, а надёжный diff требует detached payload или explicit operation.
     */
    private Optional<T> originalForUpdate(T entity) {
        if (entity.getId() == null) {
            return Optional.empty();
        }
        // Services assembled by hand in unit tests do not have the optional
        // infrastructure collaborators injected. Keep that supported while
        // the Spring path below still goes through the normal read boundary.
        if (rlsFilterActivator == null || entityManager == null || rlsReadGate == null) {
            return repository.findById((ID) entity.getId());
        }
        // Use the service read boundary so RLS/read-gate and metadata fetch policy
        // are applied before exposing the original state to application code.
        return findById((ID) entity.getId());
    }

    /** Открыть единый контекст standalone entity-операции; aggregate scope уже открыт use case. */
    private EntityEventPublisher.EventScope openEntityEventOperation(T entity) {
        if (entityEventPublisher.isEmpty()) {
            return null;
        }
        EntityEventPublisher publisher = entityEventPublisher.get();
        if (publisher.isAggregateOperation(getDomainClass())) {
            return null;
        }
        EventContext context = publisher.contextFor(getDomainClass(), entity.getId(),
            EventSource.SYSTEM, "entity:" + getDomainClass().getSimpleName());
        return publisher.openOperation(context);
    }

    private static void closeEventScope(EntityEventPublisher.EventScope eventScope) {
        if (eventScope != null) {
            eventScope.close();
        }
    }

    /**
     * Хук нумерации (см. {@code @Numbered}): перед валидацией присваивает авто-номера полям
     * ТОЛЬКО для новосозданных сущностей ({@code id == null}) — обновления не перенумеровываются.
     * Поле с уже заполненным значением при разрешённом ручном вводе не трогается (решает
     * правиле NumberingRule, см. NumberingService.autoValue). Порядок "до validate" важен:
     * у Nomenclature.code/ReceivingDocument.number стоят @NotBlank, и поле обязано быть
     * заполнено к моменту bean-валидации.
     */
    protected void assignNumbers(T entity) {
        if (entity.getId() != null || numberingService.isEmpty()) {
            return;
        }
        numberingService.get().assignAutoValues(entity);
    }
}
