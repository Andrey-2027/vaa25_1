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
 */
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
        return measured(context.operation(), request.type(), context.scenario(), () -> {
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
            }

            TypedQuery<T> typedQuery = entityManager.createQuery(query);
            if (graph != null) {
                typedQuery.setHint(FETCHGRAPH_HINT, graph);
            }
            Pageable pageable = boundedPage(request.pageable());
            if (pageable.isPaged()) {
                typedQuery.setFirstResult((int) pageable.getOffset());
                typedQuery.setMaxResults(pageable.getPageSize());
            }
            List<T> content = typedQuery.getResultList();

            if (!pageable.isPaged()) {
                return new PageImpl<>(content, Pageable.unpaged(), content.size());
            }
            CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
            Root<T> countRoot = countQuery.from(request.type());
            countQuery.select(blank ? cb.count(countRoot) : cb.countDistinct(countRoot));
            if (!blank) {
                countQuery.where(matchingPredicate(cb, countRoot, fields, request.term()));
            }
            long total = entityManager.createQuery(countQuery).getSingleResult();
            return new PageImpl<>(content, pageable, total);
        });
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

    private <T> T measured(DataOperation operation, Class<?> type, FetchScenario scenario,
                           Supplier<T> action) {
        long start = System.nanoTime();
        T result = action.get();
        telemetry.completed(operation, type, scenario, resultCount(result),
            System.nanoTime() - start);
        return result;
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
