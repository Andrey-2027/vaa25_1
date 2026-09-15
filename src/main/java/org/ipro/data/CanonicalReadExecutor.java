package org.ipro.data;

import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.rls.RlsReadGate;
import org.ipro.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Единый canonical read executor C4.1 (ADR-0007 §4).
 *
 * <p>Все стандартные чтения — paged list, unpaged list, detail, lookup и скалярный
 * aggregate — проходят одну последовательность:</p>
 * <ol>
 * <li>descriptor и проверка capability сценария до RLS/SQL (fail-closed: тип вне
 * каталога не получает handle, а не проходит как «permissive root»);</li>
 * <li>обязательный read gate и активация RLS;</li>
 * <li>разрешение {@code scenario plan ∪ extras} через {@link ScenarioFetchGraphResolver}
 * (углубление ровно один раз, в одном месте);</li>
 * <li>content query и отдельный count query при paging; fetch-граф не попадает в count;</li>
 * <li>telemetry seam с noop по умолчанию.</li>
 * </ol>
 *
 * <p>Executor не владеет policy: RLS, FetchPlan, InstanceName и metadata остаются
 * отдельными collaborators. Owned row не получает автономный list/detail/lookup: его
 * граница — aggregate root и {@code GenericOwnedSectionService}.</p>
 *
 * <p><b>Транзакция обязательна и принадлежит границе, а не вызывающему.</b>
 * {@code RlsFilterActivator} включает Hibernate {@code @Filter} на сессии текущего
 * {@code EntityManager}, поэтому gate и content query обязаны выполняться в одном
 * persistence context. Без активной транзакции shared {@code EntityManager} proxy
 * выдаёт каждый вызов на отдельной сессии: фильтр включался бы на временной сессии,
 * а запрос выполнялся бы на другой — то есть RLS молча не применялся бы. Раньше это
 * случайно обеспечивалось тем, что вызывающие сервисы наследовали class-level
 * {@code @Transactional} от compatibility base; canonical граница не должна от этого
 * зависеть (C4.6).</p>
 */
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class CanonicalReadExecutor {

    private static final String FETCHGRAPH_HINT = "jakarta.persistence.fetchgraph";

    @PersistenceContext
    private EntityManager entityManager;

    private final EntityDescriptorCatalog catalog;
    private final ScenarioFetchGraphResolver graphResolver;
    private final MetadataResolver metadataResolver;
    private final RlsFilterActivator rlsFilterActivator;
    private final RlsReadGate rlsReadGate;
    private final RlsPolicyEnforcer rlsPolicyEnforcer;
    private final ReadTelemetry telemetry;
    private final SearchFieldResolver searchFieldResolver;

    /**
     * Без C3-границы InstanceName: поля поиска выводятся из metadata-колонок. Hand-built
     * срезы и metadata-only контексты сохраняют прежний конструктор.
     */
    public CanonicalReadExecutor(EntityDescriptorCatalog catalog,
                                 ScenarioFetchGraphResolver graphResolver,
                                 MetadataResolver metadataResolver,
                                 RlsFilterActivator rlsFilterActivator,
                                 RlsReadGate rlsReadGate,
                                 RlsPolicyEnforcer rlsPolicyEnforcer,
                                 ReadTelemetry telemetry) {
        this(catalog, graphResolver, metadataResolver, rlsFilterActivator, rlsReadGate,
            rlsPolicyEnforcer, telemetry, null);
    }

    public CanonicalReadExecutor(EntityDescriptorCatalog catalog,
                                 ScenarioFetchGraphResolver graphResolver,
                                 MetadataResolver metadataResolver,
                                 RlsFilterActivator rlsFilterActivator,
                                 RlsReadGate rlsReadGate,
                                 RlsPolicyEnforcer rlsPolicyEnforcer,
                                 ReadTelemetry telemetry,
                                 InstanceNameResolver instanceNameResolver) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.graphResolver = Objects.requireNonNull(graphResolver, "graphResolver must not be null");
        this.metadataResolver = Objects.requireNonNull(metadataResolver, "metadataResolver must not be null");
        this.rlsFilterActivator = Objects.requireNonNull(rlsFilterActivator, "rlsFilterActivator must not be null");
        this.rlsReadGate = Objects.requireNonNull(rlsReadGate, "rlsReadGate must not be null");
        // Optional: legacy/slice-контексты без policy enforcer используют gate напрямую.
        this.rlsPolicyEnforcer = rlsPolicyEnforcer;
        this.telemetry = telemetry == null ? ReadTelemetry.noop() : telemetry;
        this.searchFieldResolver = new SearchFieldResolver(metadataResolver, instanceNameResolver);
    }

    /** Descriptor типа — для диагностики и вызывающих, которым нужна причина. */
    public EntityDescriptor descriptorOf(Class<?> type) {
        return catalog.descriptorOf(type);
    }

    /**
     * Строгий read gate: CHECK_ONLY без гранта на чтение → пустые результаты. При
     * наличии {@link RlsPolicyEnforcer} дополнительно включаются mandatory FILTERABLE
     * predicates.
     */
    public boolean canRead(Class<?> type) {
        if (rlsPolicyEnforcer != null) {
            return rlsPolicyEnforcer.prepareRead(type, entityManager);
        }
        return rlsReadGate.canRead(type, CurrentUser.username());
    }

    /** Paged list: content query + отдельный count query. */
    public <T> Page<T> readPage(PageRead<T> request) {
        Objects.requireNonNull(request, "request must not be null");
        return measured(DataOperation.LIST, request.type(), request.scenario(), () -> {
            requireScenario(request.type(), request.scenario());
            if (!canRead(request.type())) {
                return Page.empty(request.pageable());
            }
            requireSortWithoutCollection(request.type(), request.pageable());
            rlsFilterActivator.ensureRlsEnabled(entityManager);
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<T> dataQuery = cb.createQuery(request.type());
            Root<T> root = dataQuery.from(request.type());
            dataQuery.select(root);
            applySpec(request.filter(), root, dataQuery, cb);
            applySort(request.pageable(), root, dataQuery, cb);

            TypedQuery<T> typedQuery = entityManager.createQuery(dataQuery);
            EntityGraph<T> graph = graphResolver.resolve(entityManager, request.type(),
                request.scenario(), request.additionalPaths());
            if (graph != null) {
                typedQuery.setHint(FETCHGRAPH_HINT, graph);
            }
            Pageable pageable = request.pageable();
            if (pageable.isPaged()) {
                typedQuery.setFirstResult((int) pageable.getOffset());
                typedQuery.setMaxResults(pageable.getPageSize());
            }
            List<T> content = typedQuery.getResultList();

            CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
            Root<T> countRoot = countQuery.from(request.type());
            applySpec(request.filter(), countRoot, countQuery, cb);
            // Parity с Spring Data: если Specification пометила запрос distinct (join по
            // to-many коллекции), count тоже обязан быть distinct, иначе totalElements
            // завышается на дубликаты. Проверка идёт после применения spec — именно spec
            // вызывает query.distinct(true).
            countQuery.select(countQuery.isDistinct()
                ? cb.countDistinct(countRoot) : cb.count(countRoot));
            long total = entityManager.createQuery(countQuery).getSingleResult();
            return new PageImpl<>(content, pageable, total);
        });
    }

    /** Unpaged list (compatibility {@code findAll()}) через ту же границу. */
    public <T> List<T> readAll(ListRead<T> request) {
        Objects.requireNonNull(request, "request must not be null");
        return measured(DataOperation.LIST, request.type(), request.scenario(), () -> {
            requireScenario(request.type(), request.scenario());
            if (!canRead(request.type())) {
                return List.of();
            }
            requireSortWithoutCollection(request.type(), request.sort());
            rlsFilterActivator.ensureRlsEnabled(entityManager);
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<T> query = cb.createQuery(request.type());
            Root<T> root = query.from(request.type());
            query.select(root);
            applySpec(request.filter(), root, query, cb);
            applySort(request.sort(), root, query, cb);
            TypedQuery<T> typedQuery = entityManager.createQuery(query);
            EntityGraph<T> graph = graphResolver.resolve(entityManager, request.type(),
                request.scenario(), request.additionalPaths());
            if (graph != null) {
                typedQuery.setHint(FETCHGRAPH_HINT, graph);
            }
            return typedQuery.getResultList();
        });
    }

    /**
     * Загрузка по id сценарием запроса: {@code DETAIL} для карточки, {@code LOOKUP} для
     * значения выбора (у него свои объявленные {@code @Lookup(fetch)} зависимости).
     */
    public <T> Optional<T> readDetail(DetailRead<T> request) {
        Objects.requireNonNull(request, "request must not be null");
        FetchScenario scenario = request.scenario();
        DataOperation operation = scenario == FetchScenario.LOOKUP
            ? DataOperation.LOOKUP : DataOperation.DETAIL;
        return measured(operation, request.type(), scenario, () -> {
            requireScenario(request.type(), scenario);
            if (!canRead(request.type())) {
                return Optional.empty();
            }
            rlsFilterActivator.ensureRlsEnabled(entityManager);
            EntityGraph<T> graph = graphResolver.resolve(entityManager, request.type(),
                scenario, request.additionalPaths());
            if (graph != null) {
                return Optional.ofNullable(entityManager.find(request.type(), request.id(),
                    Map.of(FETCHGRAPH_HINT, graph)));
            }
            return Optional.ofNullable(entityManager.find(request.type(), request.id()));
        });
    }

    /**
     * Lookup: автокомплит и форма выбора. Тонкая проекция того же search builder'а
     * (C4.4, ADR-0007 §7): сценарий {@code LOOKUP}, мягкая проверка явных полей, выдача
     * ограничена {@code limit}. Blank term не фильтрует — возвращаются первые записи.
     */
    public <T> List<T> readLookup(LookupRead<T> request) {
        Objects.requireNonNull(request, "request must not be null");
        // limit == 0 — пусто без запроса: setMaxResults(0) у драйвера означает «без ограничения».
        if (request.limit() <= 0) {
            return List.of();
        }
        return readSearch(new SearchRead<>(request.type(), SearchContext.LOOKUP, request.term(),
            request.searchFields(), PageRequest.of(0, request.limit()),
            request.additionalPaths())).getContent();
    }

    /**
     * Серверный поиск C4.4 (ADR-0007 §7): единственный search builder для
     * list/lookup/global.
     *
     * <p>Последовательность — та же, что у остальных чтений: capability сценария →
     * read gate → RLS → fetch-граф → SQL. Поверх добавляются правила поиска:</p>
     * <ul>
     * <li>терм трактуется литерально ({@link SearchTerms});</li>
     * <li>поля поиска выводятся из одной лестницы ({@link SearchFieldResolver}); пустой
     * набор при непустом терме → пустая выдача, а blank term остаётся bounded-списком;</li>
     * <li>order детерминирован: {@code exact → prefix → substring}, затем id (для
     * {@code LOOKUP} — только id);</li>
     * <li>blank term не является фильтром: выдача bounded и упорядочена по id;</li>
     * <li>paging bounded: unpaged ограничивается {@link SearchRead#DEFAULT_PAGE_SIZE}.</li>
     * </ul>
     */
    public <T> Page<T> readSearch(SearchRead<T> request) {
        Objects.requireNonNull(request, "request must not be null");
        SearchContext context = request.context();
        return measured(context.operation(), request.type(), context.scenario(),
            () -> executeSearch(request, 0, true));
    }

    /**
     * Bounded top-N search for global results. Uses the same canonical query and security
     * boundary as {@link #readSearch(SearchRead)}, applies a per-source timeout, and skips
     * the count query because the caller only needs the returned window.
     */
    public <T> List<T> readSearchWindow(SearchRead<T> request, int limit, int timeoutMs) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.context() != SearchContext.GLOBAL) {
            throw new IllegalArgumentException("readSearchWindow требует SearchContext.GLOBAL");
        }
        if (limit <= 0) {
            return List.of();
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs должен быть больше нуля");
        }
        SearchRead<T> bounded = new SearchRead<>(request.type(), request.context(),
            request.term(), request.searchFields(), PageRequest.of(0, limit),
            request.additionalPaths());
        SearchContext context = bounded.context();
        return measured(context.operation(), bounded.type(), context.scenario(),
            () -> executeSearch(bounded, timeoutMs, false).getContent());
    }

    private <T> Page<T> executeSearch(SearchRead<T> request, int timeoutMs,
                                      boolean countTotal) {
        SearchContext context = request.context();
        requireScenario(request.type(), context.scenario());
        if (!canRead(request.type())) {
            return Page.empty(request.pageable());
        }
        rlsFilterActivator.ensureRlsEnabled(entityManager);

        boolean blank = SearchTerms.isBlank(request.term());
        List<String> fields = searchFieldResolver.resolve(request.type(),
            request.searchFields(), context.strictExplicitFields());
        if (fields.isEmpty() && !blank) {
            return Page.empty(request.pageable());
        }

        EntityGraph<T> graph = graphResolver.resolve(entityManager, request.type(),
            context.scenario(), request.additionalPaths());
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(request.type());
        Root<T> root = query.from(request.type());
        Path<?> idPath = root.get(SearchFieldResolver.idFieldName(request.type()));

        if (blank) {
            // Blank term не является фильтром: bounded-выдача по id, а не «вся таблица».
            query.select(root).orderBy(cb.asc(idPath));
        } else {
            query.select(root)
                .where(matchingPredicate(cb, root, fields, request.term()))
                .orderBy(context.ranked()
                    ? List.of(cb.asc(rankExpression(cb, root, fields, request.term())),
                        cb.asc(idPath))
                    : List.of(cb.asc(idPath)));
            // C4.8: страховка на случай, если поисковое поле всё же проходит через
            // to-many-коллекцию: LEFT JOIN в predicate/rank размножает root-строки, count
            // считает countDistinct, и без distinct content разошёлся бы с totalElements.
            // Политикой такой путь запрещён (`SearchFieldResolver` отклоняет его в strict-
            // режиме), поэтому ветка недостижима через стандартный резолвер — но остаётся,
            // потому что пришедшие извне поля executor не перепроверяет.
            if (multipliesRows(request.type(), fields)) {
                query.distinct(true);
            }
        }

        TypedQuery<T> typedQuery = entityManager.createQuery(query);
        if (graph != null) {
            typedQuery.setHint(FETCHGRAPH_HINT, graph);
        }
        if (timeoutMs > 0) {
            typedQuery.setHint("jakarta.persistence.query.timeout", timeoutMs);
        }
        Pageable pageable = boundedPage(request.pageable());
        if (pageable.isPaged()) {
            typedQuery.setFirstResult((int) pageable.getOffset());
            typedQuery.setMaxResults(pageable.getPageSize());
        }
        List<T> content = typedQuery.getResultList();

        if (!countTotal || !pageable.isPaged()) {
            return new PageImpl<>(content, pageable, content.size());
        }
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<T> countRoot = countQuery.from(request.type());
        countQuery.select(blank ? cb.count(countRoot) : cb.countDistinct(countRoot));
        if (!blank) {
            countQuery.where(matchingPredicate(cb, countRoot, fields, request.term()));
        }
        long total = entityManager.createQuery(countQuery).getSingleResult();
        return new PageImpl<>(content, pageable, total);
    }

    /** Подстрочный предикат по всем полям; поля с соединением не размножают строки (countDistinct). */
    private Predicate matchingPredicate(CriteriaBuilder cb, Root<?> root, List<String> fields,
                                        String term) {
        String pattern = SearchTerms.containsPattern(term);
        List<Predicate> predicates = new ArrayList<>(fields.size());
        for (String field : fields) {
            Expression<String> path = stringPath(root, field);
            if (path != null) {
                predicates.add(cb.like(cb.lower(path), pattern, SearchTerms.LIKE_ESCAPE));
            }
        }
        if (predicates.isEmpty()) {
            return cb.disjunction();
        }
        return cb.or(predicates.toArray(new Predicate[0]));
    }

    /**
     * Ранг совпадения: {@code exact} (0) → {@code prefix} (1) → {@code substring} (2).
     * Как и у глобального поиска, чтобы порядок источников не расходился.
     */
    private Expression<Integer> rankExpression(CriteriaBuilder cb, Root<?> root,
                                               List<String> fields, String term) {
        String normalized = SearchTerms.normalize(term);
        String prefix = SearchTerms.prefixPattern(term);
        List<Predicate> exact = new ArrayList<>(fields.size());
        List<Predicate> prefixed = new ArrayList<>(fields.size());
        for (String field : fields) {
            Expression<String> path = stringPath(root, field);
            if (path == null) {
                continue;
            }
            Expression<String> lower = cb.lower(path);
            exact.add(cb.equal(lower, normalized));
            prefixed.add(cb.like(lower, prefix, SearchTerms.LIKE_ESCAPE));
        }
        return cb.<Integer>selectCase()
            .when(cb.or(exact.toArray(new Predicate[0])), 0)
            .when(cb.or(prefixed.toArray(new Predicate[0])), 1)
            .otherwise(2);
    }

    /** Строковый путь с LEFT-соединениями для вложенных полей; null — поле не строковое. */
    private Expression<String> stringPath(Root<?> root, String field) {
        String[] segments = field.split("\\.");
        From<?, ?> from = root;
        for (int i = 0; i < segments.length - 1; i++) {
            from = from.join(segments[i], JoinType.LEFT);
        }
        Path<?> path = from.get(segments[segments.length - 1]);
        return path.getJavaType() == String.class ? path.as(String.class) : null;
    }

    /**
     * C4.8: пересекает ли поисковое поле to-many-коллекцию. Проверка идёт по JPA metamodel,
     * а не по разбору имени: только {@code PluralAttribute} в пути действительно размножает
     * строки при join. Знание нужно, чтобы content-query и count-query принимали одно и то же
     * решение о distinct.
     */
    private boolean multipliesRows(Class<?> rootType, List<String> fields) {
        if (fields.isEmpty()) {
            return false;
        }
        try {
            jakarta.persistence.metamodel.Metamodel metamodel = entityManager.getMetamodel();
            for (String field : fields) {
                if (fieldCrossesCollection(metamodel, rootType, field)) {
                    return true;
                }
            }
        } catch (RuntimeException noMetamodelInSlice) {
            // Слайс-контексты без полного metamodel: консервативно считаем, что размножения нет,
            // как и до C4.8 (поля всё равно приходят уже провалидированными резолвером).
            return false;
        }
        return false;
    }

    private static boolean fieldCrossesCollection(jakarta.persistence.metamodel.Metamodel metamodel,
                                                  Class<?> rootType, String field) {
        Class<?> current = rootType;
        for (String segment : field.split("\\.")) {
            jakarta.persistence.metamodel.ManagedType<?> managed;
            try {
                managed = metamodel.managedType(current);
            } catch (IllegalArgumentException notManaged) {
                return false;
            }
            jakarta.persistence.metamodel.Attribute<?, ?> attribute = null;
            for (jakarta.persistence.metamodel.Attribute<?, ?> candidate : managed.getAttributes()) {
                if (candidate.getName().equals(segment)) {
                    attribute = candidate;
                    break;
                }
            }
            if (attribute == null) {
                return false;
            }
            if (attribute instanceof jakarta.persistence.metamodel.PluralAttribute<?, ?, ?>) {
                return true;
            }
            current = attribute.getJavaType();
        }
        return false;
    }

    /**
     * C4.8: сортировка по to-many-пути отклоняется, а не «эмулируется» distinct'ом.
     *
     * <p>Порядок корня по элементу коллекции не определён: у корня несколько элементов, и
     * выбранный для сравнения произволен. Прежняя реализация добавляла {@code distinct} и
     * делала вид, что поддержка есть, но в PostgreSQL это ещё и невалидно:
     * при {@code SELECT DISTINCT} выражения {@code ORDER BY} обязаны присутствовать в списке
     * выборки, а путь идёт по join'нутой коллекции (в H2, на котором идут тесты, ошибка не
     * воспроизводится). Отказ дешевле неопределённого порядка: вызывающий получает названную
     * причину до SQL, а не другой порядок или пустую страницу.</p>
     */
    private void requireSortWithoutCollection(Class<?> rootType, Pageable pageable) {
        String offending = collectionSortProperty(rootType, pageable);
        if (offending == null) {
            return;
        }
        throw new IllegalArgumentException("Сортировка по to-many-пути «" + offending
            + "» на " + rootType.getSimpleName() + " не поддерживается: порядок корня по"
            + " элементу коллекции не определён, а SELECT DISTINCT с ORDER BY по join'нутой"
            + " коллекции невалиден в PostgreSQL. Сортируйте по полю корня или по"
            + " to-one-ассоциации.");
    }

    /** Первое свойство сортировки, путь которого проходит через {@code PluralAttribute}. */
    private String collectionSortProperty(Class<?> rootType, Pageable pageable) {
        if (pageable == null || pageable.getSort().isUnsorted()) {
            return null;
        }
        try {
            jakarta.persistence.metamodel.Metamodel metamodel = entityManager.getMetamodel();
            for (Sort.Order order : pageable.getSort()) {
                String property = order.getProperty();
                if (property != null && fieldCrossesCollection(metamodel, rootType, property)) {
                    return property;
                }
            }
        } catch (RuntimeException noMetamodelInSlice) {
            // Слайс-контексты без полного metamodel: размножение строк здесь не наблюдаемо,
            // поэтому запрет не выдумывается и поведение прежнее.
            return null;
        }
        return null;
    }

    private static Pageable boundedPage(Pageable pageable) {
        if (pageable != null && pageable.isPaged()) {
            return pageable;
        }
        return SearchRead.defaultPage();
    }

    /** Скалярный aggregate (compatibility {@code BaseService.sum}). */
    public Number readSum(Class<?> type, String fieldName, Specification<?> spec) {
        Objects.requireNonNull(type, "type must not be null");
        return measured(DataOperation.LIST, type, FetchScenario.LIST, () -> {
            requireScenario(type, FetchScenario.LIST);
            if (!canRead(type)) {
                return 0;
            }
            rlsFilterActivator.ensureRlsEnabled(entityManager);
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<Object> query = cb.createQuery();
            @SuppressWarnings("unchecked")
            Root<Object> root = (Root<Object>) (Root<?>) query.from(type);
            query.select(cb.sum(root.get(fieldName)));
            if (spec != null) {
                @SuppressWarnings("unchecked")
                Specification<Object> typedSpec = (Specification<Object>) spec;
                query.where(typedSpec.toPredicate(root, query, cb));
            }
            Object result = entityManager.createQuery(query).getSingleResult();
            return result != null ? (Number) result : 0;
        });
    }

    // ---------------------------------------------------------------- internals

    /**
     * Capability типа — enforcement-граница, а не декларация: сценарий, не разрешённый
     * descriptor'ом, отклоняется до RLS, provider callback и SQL (ADR-0007 §2). Так
     * owned row не получает автономного handle, internal store не читается через canonical
     * path, а тип вне каталога не становится permissive root.
     */
    private void requireScenario(Class<?> type, FetchScenario scenario) {
        EntityDescriptor descriptor = catalog.descriptorOf(type);
        if (descriptor.capabilities().allows(scenario)) {
            return;
        }
        if (descriptor.exposure() == EntityExposure.OWNED_ROW) {
            throw new IllegalStateException(type.getSimpleName()
                + " — строка owned-секции и не имеет автономного " + scenario
                + "-чтения. Секция читается через aggregate boundary владельца ("
                + descriptor.reason() + ").");
        }
        throw new IllegalStateException(type.getSimpleName() + " не имеет автономного "
            + scenario + "-чтения в canonical data path: " + descriptor.exposure()
            + " («" + descriptor.reason() + "», policy: "
            + descriptor.capabilities().reason() + ").");
    }

    private void applySpec(Specification<?> spec, Root<?> root, CriteriaQuery<?> query,
                           CriteriaBuilder cb) {
        if (spec == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Specification<Object> typed = (Specification<Object>) spec;
        @SuppressWarnings("unchecked")
        Root<Object> typedRoot = (Root<Object>) root;
        Predicate predicate = typed.toPredicate(typedRoot, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }
    }

    private void applySort(Pageable pageable, Root<?> root, CriteriaQuery<?> query,
                           CriteriaBuilder cb) {
        if (pageable == null || pageable.getSort().isUnsorted()) {
            return;
        }
        List<jakarta.persistence.criteria.Order> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            for (Path<?> path : sortPaths(root, order.getProperty())) {
                orders.add(order.isAscending() ? cb.asc(path) : cb.desc(path));
            }
        }
        query.orderBy(orders);
    }

    /**
     * Path'ы для ORDER BY: обычное поле — {@code root.get}; путь через точку — LEFT JOIN
     * (сортировка не выкидывает строки с незаполненной ссылкой); ссылочная колонка
     * разворачивается в {@code displaySortFields} целевой сущности.
     */
    private List<Path<?>> sortPaths(Root<?> root, String property) {
        String[] segments = property.split("\\.");
        jakarta.persistence.criteria.From<?, ?> from = root;
        for (int i = 0; i < segments.length - 1; i++) {
            from = from.join(segments[i], jakarta.persistence.criteria.JoinType.LEFT);
        }
        String last = segments[segments.length - 1];
        List<String> displayFields = displaySortFieldsFor(root.getJavaType(), property);
        if (!displayFields.isEmpty()) {
            jakarta.persistence.criteria.From<?, ?> target =
                from.join(last, jakarta.persistence.criteria.JoinType.LEFT);
            return displayFields.stream().<Path<?>>map(target::get).toList();
        }
        List<Path<?>> single = new ArrayList<>(1);
        single.add(from.get(last));
        return single;
    }

    private List<String> displaySortFieldsFor(Class<?> rootType, String property) {
        try {
            ColumnPath columnPath = ColumnPath.resolve(rootType, property);
            if (columnPath.getResolvedType() != FieldType.ENTITY_REFERENCE) {
                return List.of();
            }
            return metadataResolver.resolve(columnPath.getJavaType()).getDisplaySortFields();
        } catch (IllegalArgumentException invalidPathOrNoMetadata) {
            return List.of();
        }
    }

    /**
     * C4.8: единая точке telemetry для всех публичных read-overloads. Успех и отказ
     * различаются исходом, а не только фактом завершения: без этого отказ pipeline
     * (например, capability/RLS) вообще не был виден в telemetry.
     */
    private <T> T measured(DataOperation operation, Class<?> type, FetchScenario scenario,
                           Supplier<T> action) {
        long start = System.nanoTime();
        try {
            T result = action.get();
            telemetry.completed(operation, type, scenario, ReadTelemetry.ReadOutcome.SUCCESS,
                resultCount(result), System.nanoTime() - start);
            return result;
        } catch (RuntimeException | Error failure) {
            telemetry.completed(operation, type, scenario, ReadTelemetry.ReadOutcome.FAILED,
                0, System.nanoTime() - start);
            throw failure;
        }
    }

    private static int resultCount(Object result) {
        if (result instanceof java.util.Collection<?> collection) {
            return collection.size();
        }
        if (result instanceof Page<?> page) {
            // Страница — не один результат: телеметрия считает размер страницы, а не факт
            // её возврата.
            return page.getNumberOfElements();
        }
        if (result instanceof Optional<?> optional) {
            return optional.isPresent() ? 1 : 0;
        }
        return result == null ? 0 : 1;
    }
}
