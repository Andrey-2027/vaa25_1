package org.ipro.register;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.ipro.data.EntityDataAccess;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Canonical paged latest-value slice. The winner predicate is a portable correlated
 * {@code NOT EXISTS} Criteria expression and is passed to {@link EntityDataAccess},
 * so content and count keep the canonical C5/RLS, FetchPlan, exposure, and telemetry gates.
 */
public final class RegisterSliceReader<T> {
    public static final int MAX_PAGE_SIZE = 500;

    /** Верхняя граница набора ключей одного batch-чтения: превышение — явный отказ, не усечение. */
    public static final int MAX_BATCH_KEYS = 200;

    private final Class<T> type;
    private final EntityDataAccess access;
    private final RegisterDescriptor descriptor;
    private final Map<String, DimensionPath> dimensions;
    private final Map<String, Class<?>> dimensionTypes;
    private final String idAttribute;

    public RegisterSliceReader(Class<T> type, EntityDataAccess access, RegisterRegistry registry,
                               EntityManagerFactory entityManagerFactory) {
        this.type = Objects.requireNonNull(type, "type");
        this.access = Objects.requireNonNull(access, "access");
        this.descriptor = Objects.requireNonNull(registry, "registry").require(type);
        Objects.requireNonNull(entityManagerFactory, "entityManagerFactory");
        if (!descriptor.periodic()) {
            throw new IllegalArgumentException("Срез LATEST требует периодический регистр");
        }

        EntityType<T> entity = entityManagerFactory.getMetamodel().entity(type);
        if (!entity.hasSingleIdAttribute()) {
            throw mappingError("для стабильной сортировки нужен одиночный id");
        }
        var id = entity.getId(entity.getIdType().getJavaType());
        if (id.getPersistentAttributeType() != Attribute.PersistentAttributeType.BASIC
                || !SliceSpecValidator.supportedType(id.getJavaType())) {
            throw mappingError("неподдерживаемый тип id: " + id.getJavaType().getName());
        }
        idAttribute = id.getName();

        Map<String, DimensionPath> paths = new LinkedHashMap<>();
        Map<String, Class<?>> comparisonTypes = new LinkedHashMap<>();
        for (var field : descriptor.dimensions()) {
            Attribute<? super T, ?> attribute = entity.getAttribute(field.name());
            if (field.mapping() == RegisterDescriptor.Mapping.BASIC
                    && attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.BASIC
                    && attribute.getJavaType() == field.javaType()
                    && SliceSpecValidator.supportedType(field.javaType())) {
                paths.put(field.name(), new DimensionPath(field.name(), null));
                comparisonTypes.put(field.name(), field.javaType());
            } else if (field.mapping() == RegisterDescriptor.Mapping.TO_ONE
                    && attribute instanceof SingularAttribute<?, ?> singular && singular.isAssociation()
                    && (attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.MANY_TO_ONE
                        || attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.ONE_TO_ONE)) {
                EntityType<?> target = entityManagerFactory.getMetamodel().entity(field.javaType());
                if (!target.hasSingleIdAttribute()) {
                    throw mappingError("составной id у измерения " + field.name());
                }
                var targetId = target.getId(target.getIdType().getJavaType());
                if (targetId.getPersistentAttributeType() != Attribute.PersistentAttributeType.BASIC
                        || !SliceSpecValidator.supportedType(targetId.getJavaType())) {
                    throw mappingError("неподдерживаемый id измерения " + field.name());
                }
                paths.put(field.name(), new DimensionPath(field.name(), targetId.getName()));
                comparisonTypes.put(field.name(), SliceSpecValidator.boxed(targetId.getJavaType()));
            } else {
                throw mappingError("неподдерживаемое mapping измерения " + field.name());
            }
        }
        dimensions = Map.copyOf(paths);
        dimensionTypes = Map.copyOf(comparisonTypes);

        for (var field : descriptor.resources()) {
            var attribute = entity.getAttribute(field.name());
            if (field.mapping() == RegisterDescriptor.Mapping.TO_ONE
                    && attribute instanceof SingularAttribute<?, ?> association && association.isAssociation()
                    && attribute.getJavaType() == field.javaType()
                    && (attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.MANY_TO_ONE
                        || attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.ONE_TO_ONE)) {
                // A reference resource is returned as a value, not used to choose a winner.
                // Resource predicates remain subject to SliceSpecValidator's scalar contract.
                entityManagerFactory.getMetamodel().entity(field.javaType());
            } else {
                requireScalar(attribute, field.name(), field.javaType());
            }
        }
        var period = descriptor.period().orElseThrow();
        requireScalar(entity.getAttribute(period.name()), period.name(), period.javaType());
    }

    /**
     * Read one page of winners. Dimension predicates restrict candidates before selection;
     * resource/period predicates in {@code afterSlice} filter only the selected winners.
     */
    public Page<T> latest(SliceSpec spec, Pageable pageable) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(pageable, "pageable");
        if (spec.registerType() != type) {
            throw new IllegalArgumentException("SLICE_TYPE: спецификация относится к другому регистру");
        }
        SliceSpecValidator.validate(descriptor, spec, dimensionTypes);
        Pageable stable = stablePageable(pageable);
        return access.list(type, winnerSpecification(spec, null), stable);
    }

    /** Read the values preceding an editable recorder's own replacement, using the same winner order. */
    public Page<T> latestExcludingRecorder(SliceSpec spec, RecorderRef excluded, Pageable pageable) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(excluded, "excluded");
        Objects.requireNonNull(pageable, "pageable");
        requireExcludedRecorder(excluded);
        SliceSpecValidator.validate(descriptor, spec, dimensionTypes);
        return access.list(type, winnerSpecification(spec, excluded), stablePageable(pageable));
    }

    /**
     * Одна строка-победитель тем же предикатом, что у {@link #latest}, но без count-запроса (Р9):
     * там, где нужна конкретная строка, total не считается. Порядок среза тот же, что у страницы.
     */
    public Optional<T> first(SliceSpec spec) {
        return first(spec, null);
    }

    /** Точка за вычетом собственного регистратора: тот же победитель, что у {@link #latestExcludingRecorder}. */
    public Optional<T> first(SliceSpec spec, RecorderRef excluded) {
        Objects.requireNonNull(spec, "spec");
        if (spec.registerType() != type) {
            throw new IllegalArgumentException("SLICE_TYPE: спецификация относится к другому регистру");
        }
        requireExcludedRecorder(excluded);
        SliceSpecValidator.validate(descriptor, spec, dimensionTypes);
        return access.first(type, winnerSpecification(spec, excluded), stablePageable(PageRequest.of(0, 1)).getSort());
    }

    /**
     * Набор полных ключей на одну общую дату: последняя строка на каждый набор измерений (Р9).
     *
     * <p>Один set-based запрос на весь набор: ключи становятся дизъюнкцией наборов измерений, а не
     * отдельным point read на каждую строку. Семантика победителя, права, FetchPlan, экспозиция и
     * telemetry — те же, что у {@link #latest}: батч-чтение не отдельный канал данных.</p>
     *
     * <p>Контракт набора: одна дата {@code asOf} для всех ключей; ключ содержит ровно измерения
     * регистра, ссылки передаются сохранёнными идентификаторами (как в {@link SliceSpec.Condition});
     * пустой набор даёт пустой результат; дубли читаются один раз; отсутствующие и запрещённые
     * правами строки просто не появляются — их отсутствие обрабатывает вызывающий. Дата у каждого
     * ключа — другой контракт, а не скрытое расширение этого метода.</p>
     */
    public List<T> latestForKeys(java.time.LocalDateTime asOf, Collection<Map<String, Object>> keys) {
        return latestForKeys(asOf, keys, null);
    }

    /** Батч-срез с исключением собственного регистратора — для строк редактируемого документа. */
    public List<T> latestForKeys(java.time.LocalDateTime asOf, Collection<Map<String, Object>> keys,
                                 RecorderRef excluded) {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(keys, "keys");
        requireExcludedRecorder(excluded);
        List<Map<String, Object>> requested = new ArrayList<>();
        Set<Map<String, Object>> seen = new HashSet<>();
        for (Map<String, Object> key : keys) {
            Objects.requireNonNull(key, "key");
            // LinkedHashMap.equals сравнивает по содержимому: дубль ключа не удваивает чтение.
            Map<String, Object> copy = new LinkedHashMap<>(key);
            if (seen.add(copy)) requested.add(copy);
        }
        if (requested.isEmpty()) return List.of();
        if (requested.size() > MAX_BATCH_KEYS) {
            throw new IllegalArgumentException("SLICE_BATCH_SIZE: набор ключей не должен превышать "
                + MAX_BATCH_KEYS + " полных наборов измерений, получено " + requested.size());
        }
        List<SliceSpec> keySpecs = new ArrayList<>();
        for (Map<String, Object> key : requested) keySpecs.add(keySpec(asOf, key));
        Specification<T> requestedKeys = (root, query, cb) -> {
            List<Predicate> alternatives = new ArrayList<>();
            for (SliceSpec keySpec : keySpecs) {
                alternatives.add(cb.and(candidateFilters(root, cb, keySpec).toArray(Predicate[]::new)));
            }
            return cb.or(alternatives.toArray(Predicate[]::new));
        };
        Specification<T> winners = winnerSpecification(SliceSpec.latest(type, asOf), excluded).and(requestedKeys);
        // Одна страница на весь набор: добавочный count не зависит от числа ключей и не является N+1.
        return access.list(type, winners, stablePageable(PageRequest.of(0, requested.size()))).getContent();
    }

    /** Ключ батча как обычная спецификация среза: тот же валидатор типов и измерений. */
    private SliceSpec keySpec(java.time.LocalDateTime asOf, Map<String, Object> key) {
        Set<String> expected = new LinkedHashSet<>(descriptor.dimensionNames());
        if (!expected.equals(key.keySet())) {
            throw new IllegalArgumentException("SLICE_BATCH_KEY: ключ обязан содержать ровно измерения регистра "
                + expected + ", получено " + key.keySet());
        }
        List<SliceSpec.Condition> conditions = new ArrayList<>();
        for (var dimension : descriptor.dimensions()) {
            conditions.add(new SliceSpec.Condition(dimension.name(), SliceSpec.Operator.EQ, key.get(dimension.name())));
        }
        SliceSpec spec = new SliceSpec(type, SliceSpec.Mode.LATEST, asOf, conditions, List.of());
        SliceSpecValidator.validate(descriptor, spec, dimensionTypes);
        return spec;
    }

    private void requireExcludedRecorder(RecorderRef excluded) {
        if (excluded != null && (!descriptor.recorded()
                || !descriptor.recorderTypeCodes().contains(excluded.getTypeCode()))) {
            throw new IllegalArgumentException("SLICE_RECORDER_TYPE: исключаемый регистратор не разрешён регистром");
        }
    }

    /** Structured list filters: dimension predicates restrict candidates; other predicates test winners. */
    public Specification<T> latestListSpecification(java.time.LocalDateTime asOf,
            Specification<T> dimensionFilter, Specification<T> afterFilter) {
        SliceSpec spec = SliceSpec.latest(type, asOf);
        return (root, query, cb) -> {
            Path<?> period = root.get(descriptor.period().orElseThrow().name());
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(lessThanOrEqual(period, spec.asOf(), cb));
            if (dimensionFilter != null) predicates.add(dimensionFilter.toPredicate(root, query, cb));
            if (afterFilter != null) predicates.add(afterFilter.toPredicate(root, query, cb));
            Subquery<Integer> newer = query.subquery(Integer.class);
            Root<T> candidate = newer.from(type);
            List<Predicate> candidates = new ArrayList<>();
            candidates.add(lessThanOrEqual(candidate.get(descriptor.period().orElseThrow().name()), spec.asOf(), cb));
            if (dimensionFilter != null) candidates.add(dimensionFilter.toPredicate(candidate, query, cb));
            for (var dimension : descriptor.dimensions())
                candidates.add(cb.equal(dimensionPath(candidate, dimension.name()), dimensionPath(root, dimension.name())));
            candidates.add(lexicographicallyGreater(candidate, root, cb));
            newer.select(cb.literal(1)).where(cb.and(candidates.toArray(Predicate[]::new)));
            predicates.add(cb.not(cb.exists(newer)));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Stable ordering for list columns; canonical metadata validation remains at the read port. */
    public Pageable latestListPageable(Pageable pageable) {
        if (!pageable.isPaged() || pageable.getPageSize() > MAX_PAGE_SIZE || pageable.getOffset() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Недопустимый размер страницы среза");
        List<Sort.Order> orders = new ArrayList<>(pageable.getSort().toList());
        if (orders.stream().noneMatch(order -> order.getProperty().equals(idAttribute))) orders.add(Sort.Order.asc(idAttribute));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
    }

    private Specification<T> winnerSpecification(SliceSpec spec, RecorderRef excluded) {
        return (root, query, cb) -> {
            Path<?> period = root.get(descriptor.period().orElseThrow().name());
            List<Predicate> outer = candidateFilters(root, cb, spec);
            excludeRecorder(root, cb, excluded, outer);
            outer.add(lessThanOrEqual(period, spec.asOf(), cb));
            outer.addAll(afterSlicePredicates(root, cb, spec.afterSlice()));

            Subquery<Integer> newer = query.subquery(Integer.class);
            Root<T> candidate = newer.from(type);
            List<Predicate> newerRows = candidateFilters(candidate, cb, spec);
            excludeRecorder(candidate, cb, excluded, newerRows);
            newerRows.add(lessThanOrEqual(candidate.get(descriptor.period().orElseThrow().name()), spec.asOf(), cb));
            for (var dimension : descriptor.dimensions()) {
                Path<?> candidateValue = dimensionPath(candidate, dimension.name());
                Path<?> outerValue = dimensionPath(root, dimension.name());
                newerRows.add(cb.equal(candidateValue, outerValue));
            }
            newerRows.add(lexicographicallyGreater(candidate, root, cb));
            newer.select(cb.literal(1)).where(cb.and(newerRows.toArray(Predicate[]::new)));
            outer.add(cb.not(cb.exists(newer)));
            return cb.and(outer.toArray(Predicate[]::new));
        };
    }

    private static void excludeRecorder(Root<?> root, CriteriaBuilder cb, RecorderRef excluded,
                                        List<Predicate> predicates) {
        if (excluded != null) {
            Path<?> recorder = root.get("recorder");
            predicates.add(cb.not(cb.and(cb.equal(recorder.get("typeCode"), excluded.getTypeCode()),
                cb.equal(recorder.get("id"), excluded.getId()))));
        }
    }

    private List<Predicate> candidateFilters(Root<T> root, CriteriaBuilder cb, SliceSpec spec) {
        List<Predicate> predicates = new ArrayList<>();
        for (var condition : spec.dimensions()) {
            predicates.add(compare(dimensionPath(root, condition.field()), condition, cb));
        }
        var intent = spec.recorderReadIntent();
        if (intent.scope() == SliceSpec.RecorderReadIntent.Scope.SELECTED_RECORDERS) {
            List<Predicate> alternatives = new ArrayList<>();
            for (RecorderRef reference : intent.selectedRecorders()) {
                Path<?> recorder = root.get("recorder");
                alternatives.add(cb.and(
                    cb.equal(recorder.get("typeCode"), reference.getTypeCode()),
                    cb.equal(recorder.get("id"), reference.getId())));
            }
            predicates.add(cb.or(alternatives.toArray(Predicate[]::new)));
        }
        return predicates;
    }

    private List<Predicate> afterSlicePredicates(Root<T> root, CriteriaBuilder cb,
                                                  List<SliceSpec.Condition> conditions) {
        List<Predicate> predicates = new ArrayList<>();
        for (var condition : conditions) {
            predicates.add(compare(root.get(condition.field()), condition, cb));
        }
        return predicates;
    }

    private Path<?> dimensionPath(Root<T> root, String field) {
        DimensionPath path = dimensions.get(field);
        if (path == null) throw mappingError("неизвестное измерение " + field);
        Path<?> value = root.get(path.field());
        return path.idAttribute() == null ? value : value.get(path.idAttribute());
    }

    private Predicate lexicographicallyGreater(Root<T> candidate, Root<T> current, CriteriaBuilder cb) {
        List<String> order = descriptor.winnerOrderFields();
        List<Predicate> alternatives = new ArrayList<>();
        List<Predicate> equalPrefix = new ArrayList<>();
        for (String field : order) {
            Path<?> candidateValue = candidate.get(field);
            Path<?> currentValue = current.get(field);
            alternatives.add(cb.and(concat(equalPrefix, greaterThan(candidateValue, currentValue, cb))
                .toArray(Predicate[]::new)));
            equalPrefix.add(cb.equal(candidateValue, currentValue));
        }
        return cb.or(alternatives.toArray(Predicate[]::new));
    }

    private static List<Predicate> concat(List<Predicate> prefix, Predicate tail) {
        List<Predicate> result = new ArrayList<>(prefix);
        result.add(tail);
        return result;
    }

    private Pageable stablePageable(Pageable pageable) {
        if (pageable.isUnpaged()) {
            throw new IllegalArgumentException("SLICE_PAGE: для среза требуется ограниченная страница");
        }
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("SLICE_PAGE_SIZE: размер страницы не должен превышать " + MAX_PAGE_SIZE);
        }
        if (pageable.getOffset() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("SLICE_PAGE_OFFSET: смещение страницы превышает JPA-предел");
        }
        Set<String> allowedSort = new HashSet<>(descriptor.dimensionNames());
        descriptor.period().ifPresent(field -> allowedSort.add(field.name()));
        descriptor.resources().forEach(field -> allowedSort.add(field.name()));
        for (Sort.Order order : pageable.getSort()) {
            if (!allowedSort.contains(order.getProperty())) {
                throw new IllegalArgumentException("SLICE_SORT_FIELD: сортировка запрещена по полю '"
                    + order.getProperty() + "'");
            }
        }

        List<Sort.Order> orders = new ArrayList<>(pageable.getSort().toList());
        Set<String> existing = new HashSet<>();
        orders.forEach(order -> existing.add(order.getProperty()));
        for (var dimension : descriptor.dimensions()) {
            DimensionPath path = dimensions.get(dimension.name());
            String stablePath = path.idAttribute() == null
                ? path.field() : path.field() + "." + path.idAttribute();
            if (existing.add(stablePath)) orders.add(Sort.Order.asc(stablePath));
        }
        if (existing.add(idAttribute)) orders.add(Sort.Order.asc(idAttribute));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
    }

    private static void requireScalar(Attribute<?, ?> attribute, String field, Class<?> expectedType) {
        if (attribute.getPersistentAttributeType() != Attribute.PersistentAttributeType.BASIC
                || attribute.getJavaType() != expectedType
                || !SliceSpecValidator.supportedType(expectedType)) {
            throw mappingError("неподдерживаемое scalar-поле " + field);
        }
    }

    private static IllegalArgumentException mappingError(String message) {
        return new IllegalArgumentException("SLICE_MAPPING: " + message);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Predicate compare(Path<?> path, SliceSpec.Condition condition, CriteriaBuilder cb) {
        return switch (condition.operator()) {
            case EQ -> cb.equal(path, condition.value());
            case NE -> cb.notEqual(path, condition.value());
            case GT -> cb.greaterThan((Expression) path, (Comparable) condition.value());
            case GE -> cb.greaterThanOrEqualTo((Expression) path, (Comparable) condition.value());
            case LT -> cb.lessThan((Expression) path, (Comparable) condition.value());
            case LE -> cb.lessThanOrEqualTo((Expression) path, (Comparable) condition.value());
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Predicate lessThanOrEqual(Path<?> path, Comparable value, CriteriaBuilder cb) {
        return cb.lessThanOrEqualTo((Expression) path, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Predicate greaterThan(Path<?> left, Path<?> right, CriteriaBuilder cb) {
        return cb.greaterThan((Expression) left, (Expression) right);
    }

    private record DimensionPath(String field, String idAttribute) { }
}
