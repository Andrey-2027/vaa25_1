package org.ip.application.access;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.*;
import java.util.*;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.RlsFilterActivator;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/** Privileged bounded catalogue query. Fetch and count share the same criteria builder. */
@Service
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true)
public class PermissionScopePageService {
    public record Query(int offset, int limit, String code, String name, boolean selected,
                        String sortField, boolean descending, Map<Long, PermissionPatch.Flags> draft) {
        public Query {
            if (offset < 0 || limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid page bounds");
            code = code == null ? "" : code; name = name == null ? "" : name;
            sortField = sortField == null ? "code" : sortField;
            if (!Set.of("id", "code", "name").contains(sortField)) throw new IllegalArgumentException("Unsupported sort");
            draft = Map.copyOf(draft);
            if (draft.size() > 500 || draft.keySet().stream().anyMatch(id -> id <= 0))
                throw new IllegalArgumentException("Draft limited to 500 positive ids");
            if (draft.values().stream().anyMatch(flags -> !flags.read() && (flags.update() || flags.delete())))
                throw new IllegalArgumentException("RLS draft write flags require normalized READ");
        }
    }
    public record Value(long id, String code, String name) { }
    public record Page(List<Value> values, long total, int offset, PermissionStateService.Snapshot permissions) {
        public Page { values = List.copyOf(values); }
    }
    private final EntityManager em;
    private final PermissionCatalogService catalogs;
    private final PermissionStateService states;
    private final RlsFilterActivator filters;
    public PermissionScopePageService(EntityManager em, PermissionCatalogService catalogs,
                                      PermissionStateService states, RlsFilterActivator filters) {
        this.em = em; this.catalogs = catalogs; this.states = states; this.filters = filters;
    }
    public Page page(SubjectRef.Identity identity, String scopeId, Query query) {
        var scope = catalogs.catalog().scope(scopeId).orElseThrow(() -> new IllegalArgumentException("Unknown editable scope"));
        var subject = states.scope(identity, scopeId, List.of());
        // These scalar fields are validated by the JPA metamodel, never interpolated SQL.
        var type = em.getMetamodel().entity(scope.valueType());
        for (String field : List.of("code", "name")) {
            if (type.getAttribute(field).getJavaType() != String.class)
                throw new IllegalArgumentException("Scope needs string code/name metadata: " + scopeId);
        }
        Page result = filters.withDimensionAdministration(em, scopeId, () -> {
            var cb = em.getCriteriaBuilder();
            var count = cb.createQuery(Long.class);
            var cr = count.from(scope.valueType());
            count.select(cb.count(cr)).where(predicate(cb, count, cr, scopeId, subject, query));
            long total = em.createQuery(count).getSingleResult();
            // Lazy Grid owns the requested range; never substitute another range's rows.
            int offset = query.offset();
            var fetch = cb.createTupleQuery();
            var root = fetch.from(scope.valueType());
            fetch.multiselect(root.get("id"), root.get("code"), root.get("name"))
                .where(predicate(cb, fetch, root, scopeId, subject, query));
            Expression<?> sort = query.sortField().equals("id") ? root.get("id") : cb.lower(root.get(query.sortField()));
            fetch.orderBy(query.descending() ? cb.desc(sort) : cb.asc(sort), cb.asc(root.get("id")));
            List<Value> values = em.createQuery(fetch).setFirstResult(offset).setMaxResults(query.limit())
                .getResultList().stream().map(tuple -> new Value(((Number) tuple.get(0)).longValue(),
                    tuple.get(1, String.class), tuple.get(2, String.class))).toList();
            // Restore the normal scope before reading grants / resolving subject again.
            return new Page(values, total, offset, null);
        });
        return new Page(result.values(), result.total(), result.offset(), states.scopeWithDraft(identity, scopeId,
            result.values().stream().map(Value::id).toList(), query.draft()));
    }

    public record EntityPage<T>(org.springframework.data.domain.Page<T> page, PermissionStateService.Snapshot permissions) { }

    /** Typed adapter: FilterGrid Specification belongs to the registered JPA source, never a DTO. */
    public <T> EntityPage<T> entities(SubjectRef.Identity identity, String scopeId, Class<T> source,
            org.springframework.data.jpa.domain.Specification<T> specification,
            org.springframework.data.domain.Pageable pageable, boolean selected,
            Map<Long, PermissionPatch.Flags> draft) {
        return entitiesWithPatch(identity, scopeId, source, specification, pageable, selected, draft, null);
    }

    public <T> EntityPage<T> entitiesWithPatch(SubjectRef.Identity identity, String scopeId, Class<T> source,
            org.springframework.data.jpa.domain.Specification<T> specification,
            org.springframework.data.domain.Pageable pageable, boolean selected,
            Map<Long, PermissionPatch.Flags> draft, PermissionPatch globalDraft) {
        var scope = catalogs.catalog().scope(scopeId).orElseThrow(() -> new IllegalArgumentException("Unknown scope"));
        if (scope.valueType() != source) throw new IllegalArgumentException("Wrong registered source type");
        var request = new Query(Math.toIntExact(pageable.getOffset()), pageable.getPageSize(), "", "", selected, "id", false, draft);
        var subject = states.scope(identity, scopeId, List.of());
        var page = filters.withDimensionAdministration(em, scopeId, () -> {
            var cb = em.getCriteriaBuilder();
            var count = cb.createQuery(Long.class); var cr = count.from(source);
            var cp = specification == null ? cb.conjunction() : specification.toPredicate(cr, count, cb);
            count.select(cb.count(cr)).where(cb.and(cp == null ? cb.conjunction() : cp, predicate(cb, count, cr, scopeId, subject, request, globalDraft)));
            long total = em.createQuery(count).getSingleResult();
            var fetch = cb.createQuery(source); var root = fetch.from(source);
            var fp = specification == null ? cb.conjunction() : specification.toPredicate(root, fetch, cb);
            fetch.select(root).where(cb.and(fp == null ? cb.conjunction() : fp, predicate(cb, fetch, root, scopeId, subject, request, globalDraft)));
            var orders = new ArrayList<Order>();
            pageable.getSort().forEach(sort -> {
                if (!Set.of("id", "code", "name").contains(sort.getProperty())) throw new IllegalArgumentException("Unsupported sort");
                orders.add(sort.isAscending() ? cb.asc(root.get(sort.getProperty())) : cb.desc(root.get(sort.getProperty())));
            });
            orders.add(cb.asc(root.get("id"))); fetch.orderBy(orders);
            return new org.springframework.data.domain.PageImpl<>(em.createQuery(fetch).setFirstResult(request.offset())
                .setMaxResults(request.limit()).getResultList(), pageable, total);
        });
        var ids = page.getContent().stream().map(value -> ((org.ipro.identity.IdentifiableEntity<?>) value).getId())
            .map(value -> ((Number) value).longValue()).toList();
        return new EntityPage<>(page, states.scopeWithPatch(identity, scopeId, ids, draft, globalDraft));
    }

    private Predicate predicate(CriteriaBuilder cb, CommonAbstractCriteria query, Root<?> root,
                                String scope, PermissionStateService.Snapshot snapshot, Query request) {
        return predicate(cb, query, root, scope, snapshot, request, null);
    }

    private Predicate predicate(CriteriaBuilder cb, CommonAbstractCriteria query, Root<?> root,
                                String scope, PermissionStateService.Snapshot snapshot, Query request, PermissionPatch globalDraft) {
        var predicates = new ArrayList<Predicate>();
        if (!request.code().isEmpty()) predicates.add(cb.like(cb.lower(root.get("code")), contains(request.code()), '\\'));
        if (!request.name().isEmpty()) predicates.add(cb.like(cb.lower(root.get("name")), contains(request.name()), '\\'));
        if (request.selected()) {
            var exists = query.subquery(Long.class);
            var grant = exists.from(AccessGrant.class);
            var subject = snapshot.subject();
            Predicate own = cb.and(cb.equal(grant.get("subjectType"), subject.type()), cb.equal(grant.get("subjectKey"), subject.subjectKey()));
            Predicate assigned = snapshot.roles().isEmpty() ? cb.disjunction() : cb.and(
                cb.equal(grant.get("subjectType"), AccessGrant.SubjectType.ROLE),
                grant.get("subjectKey").in(snapshot.roles().stream().map(SubjectRef::subjectKey).toList()));
            Predicate origin = cb.or(own, assigned);
            Predicate address = cb.or(cb.isNull(grant.get("dimensionValueId")), cb.equal(grant.get("dimensionValueId"), root.get("id")));
            Predicate stored = cb.conjunction();
            if (!request.draft().isEmpty()) stored = cb.not(cb.and(own, cb.equal(grant.get("dimension"), scope),
                cb.isNotNull(grant.get("dimensionValueId")),
                grant.get("dimensionValueId").in(request.draft().keySet())));
            boolean globalRead = false;
            if (globalDraft != null) {
                if (!globalDraft.subject().identity().equals(snapshot.subject().identity())) throw new IllegalArgumentException("Wrong draft subject");
                for (var change : globalDraft.changes()) {
                    if (change.address().valueId() != null || change.address().authority() != PermissionBinding.Authority.RLS
                        || !List.of(scope, "*").contains(change.address().key())) continue;
                    stored = cb.and(stored, cb.not(cb.and(own, cb.equal(grant.get("dimension"), change.address().key()), cb.isNull(grant.get("dimensionValueId")))));
                    var flags = PermissionDraftStore.flags(change.expectedRows()).apply(change.desiredFlags());
                    globalRead |= flags.read() || flags.update() || flags.delete();
                }
            }
            exists.select(grant.get("id")).where(origin, grant.get("dimension").in(List.of(scope, "*")),
                cb.isTrue(grant.get("canRead")), address, stored);
            var added = request.draft().entrySet().stream().filter(entry -> entry.getValue().read()
                || entry.getValue().update() || entry.getValue().delete()).map(Map.Entry::getKey).toList();
            // Existing scope write rights require READ, so selected membership is exactly READ.
            predicates.add(globalRead ? cb.conjunction() : added.isEmpty() ? cb.exists(exists) : cb.or(cb.exists(exists), root.get("id").in(added)));
        }
        return cb.and(predicates.toArray(Predicate[]::new));
    }
    private static String contains(String value) {
        return "%" + value.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

}
