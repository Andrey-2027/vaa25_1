package org.ipro.register;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.ipro.data.EntityDataAccess;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Canonical HISTORY/DETAIL reads for recorder-owned rows. Register authorization and RLS are
 * applied by {@link EntityDataAccess}; the referenced document is neither loaded nor authorized here.
 */
public final class RecordedRegisterReader<T> {
    private final Class<T> type;
    private final EntityDataAccess access;
    private final RegisterDescriptor descriptor;

    public RecordedRegisterReader(Class<T> type, EntityDataAccess access, RegisterRegistry registry) {
        this.type = Objects.requireNonNull(type, "type");
        this.access = Objects.requireNonNull(access, "access");
        this.descriptor = Objects.requireNonNull(registry, "registry").require(type);
        if (!descriptor.recorded()) {
            throw new IllegalArgumentException(type.getSimpleName() + " не является подчинённым регистром");
        }
    }

    /** Read a page of register history without requiring visibility of any recorder document. */
    public Page<T> history(Pageable pageable) {
        return access.list(type, null, stablePageable(Objects.requireNonNull(pageable, "pageable")));
    }

    /** Read history restricted by zero or more equality conditions on declared dimensions. */
    public Page<T> history(Map<String, ?> dimensionValues, Pageable pageable) {
        Objects.requireNonNull(dimensionValues, "dimensionValues");
        Objects.requireNonNull(pageable, "pageable");
        Map<String, Object> values = Map.copyOf(dimensionValues);
        Set<String> allowed = Set.copyOf(descriptor.dimensionNames());
        Set<String> unexpected = new TreeSet<>(values.keySet());
        unexpected.removeAll(allowed);
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException("RECORDER_HISTORY_FIELD: фильтр разрешён только по dimensions; лишние поля "
                + unexpected);
        }
        for (var dimension : descriptor.dimensions()) {
            Object value = values.get(dimension.name());
            if (values.containsKey(dimension.name()) && !dimension.javaType().isInstance(value)) {
                throw new IllegalArgumentException("RECORDER_HISTORY_TYPE: dimension '" + dimension.name()
                    + "' требует " + dimension.javaType().getName());
            }
        }
        Specification<T> specification = (Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb) -> {
            var predicates = new ArrayList<Predicate>();
            values.forEach((name, value) -> predicates.add(cb.equal(root.get(name), value)));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return access.list(type, specification, stablePageable(pageable));
    }

    /** Canonical detail read by the register row's own technical Long id. */
    public Optional<T> detail(Long id) {
        return access.detail(type, Objects.requireNonNull(id, "id"));
    }

    private Pageable stablePageable(Pageable pageable) {
        if (pageable.isUnpaged()) return pageable;
        Sort sort = pageable.getSort();
        var existing = new java.util.HashSet<String>();
        sort.forEach(order -> existing.add(order.getProperty()));
        var tieBreakers = new java.util.ArrayList<Sort.Order>();
        if (descriptor.periodic()) {
            addDescending(tieBreakers, existing, descriptor.period().orElseThrow().name());
        }
        addDescending(tieBreakers, existing, descriptor.recordingOrder().orElseThrow().name());
        addDescending(tieBreakers, existing, descriptor.lineNumber().orElseThrow().name());
        if (!tieBreakers.isEmpty()) sort = sort.and(Sort.by(tieBreakers));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    private static void addDescending(java.util.List<Sort.Order> orders, Set<String> existing, String field) {
        if (existing.add(field)) orders.add(Sort.Order.desc(field));
    }
}
