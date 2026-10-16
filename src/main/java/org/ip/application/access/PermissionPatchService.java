package org.ip.application.access;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.ipro.rls.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One transactional writer for a subject's physical rows. No full-dimension replacement. */
@Service
@PreAuthorize("hasRole('ADMIN')")
@Transactional
public class PermissionPatchService {
    public record Saved(SubjectRef subject, List<PermissionGrantAddress> addresses) {
        public Saved { addresses = List.copyOf(addresses); }
    }
    public record Diff(PermissionGrantAddress address, PermissionPatch.Flags before, PermissionPatch.Flags after) { }
    public record CommittedChange(String actor, SubjectRef subject, Instant time, List<Diff> changes) {
        public CommittedChange { changes = List.copyOf(changes); }
    }
    public static final class Conflict extends RuntimeException {
        private final List<PermissionGrantAddress> addresses;
        public Conflict(List<PermissionGrantAddress> addresses) {
            super("Права изменены другим администратором; обновите состояние перед повторным применением");
            this.addresses = List.copyOf(addresses);
        }
        public List<PermissionGrantAddress> addresses() { return addresses; }
    }
    private final PermissionSubjectLock locks;
    private final PermissionCatalogService catalogs;
    private final AccessGrantRepository grants;
    private final EntityManager em;
    private final RlsFilterActivator filters;
    private final RlsCurrentUser currentUser;
    private final ApplicationEventPublisher events;
    public PermissionPatchService(PermissionSubjectLock locks, PermissionCatalogService catalogs,
                                  AccessGrantRepository grants, EntityManager em, RlsFilterActivator filters,
                                  RlsCurrentUser currentUser, ApplicationEventPublisher events) {
        this.locks = locks; this.catalogs = catalogs; this.grants = grants; this.em = em;
        this.filters = filters; this.currentUser = currentUser; this.events = events;
    }

    /** Immutable baseline of one address, including absence; opening does not normalize stored flags. */
    public List<PermissionPatch.Snapshot> baseline(SubjectRef subject, PermissionGrantAddress address) {
        requireCurrent(subject);
        validate(address, Map.of());
        return snapshots(rows(subject, address));
    }

    public Saved save(PermissionPatch patch) {
        if (patch.changes().size() > 500) throw new IllegalArgumentException("At most 500 changed grant addresses per save");
        SubjectRef subject = requireCurrent(patch.subject());
        var current = new LinkedHashMap<PermissionGrantAddress, List<AccessGrant>>();
        var conflicts = new ArrayList<PermissionGrantAddress>();
        for (var change : patch.changes()) {
            validate(change.address(), change.desiredFlags());
            var rows = rows(subject, change.address());
            current.put(change.address(), rows);
            if (!new HashSet<>(snapshots(rows)).equals(new HashSet<>(change.expectedRows()))) conflicts.add(change.address());
        }
        if (!conflicts.isEmpty()) throw new Conflict(conflicts);
        var diffs = new ArrayList<Diff>();
        for (var change : patch.changes()) {
            var rows = current.get(change.address());
            var before = PermissionDraftStore.flags(snapshots(rows));
            var after = before.apply(change.desiredFlags());
            if (change.address().authority() == PermissionBinding.Authority.RLS)
                after = new PermissionPatch.Flags(after.read() || after.update() || after.delete(), after.update(), after.delete());
            if (!after.read() && !after.update() && !after.delete()) rows.forEach(grants::delete);
            else {
                if (rows.size() > 1) throw new Conflict(List.of(change.address()));
                var grant = rows.isEmpty() ? new AccessGrant() : rows.getFirst();
                grant.setSubjectType(subject.type()); grant.setSubjectKey(subject.subjectKey());
                grant.setDimension(change.address().key()); grant.setDimensionValueId(change.address().valueId());
                grant.setCanRead(after.read()); grant.setCanUpdate(after.update()); grant.setCanDelete(after.delete());
                grants.save(grant);
            }
            if (!before.equals(after)) diffs.add(new Diff(change.address(), before, after));
        }
        grants.flush();
        if (!diffs.isEmpty()) events.publishEvent(new CommittedChange(currentUser.requireAuthenticatedUsername(), subject, Instant.now(), diffs));
        return new Saved(subject, List.copyOf(current.keySet()));
    }

    private SubjectRef requireCurrent(SubjectRef expected) {
        var current = locks.acquire(expected.identity());
        if (!current.subjectKey().equals(expected.subjectKey())) throw new Conflict(List.of());
        return current;
    }
    private List<AccessGrant> rows(SubjectRef subject, PermissionGrantAddress address) {
        var result = em.createQuery("select g from AccessGrant g where g.subjectType=:type and g.subjectKey=:key "
            + "and g.dimension=:dimension and ((:value is null and g.dimensionValueId is null) or g.dimensionValueId=:value)", AccessGrant.class)
            .setParameter("type", subject.type()).setParameter("key", subject.subjectKey())
            .setParameter("dimension", address.key()).setParameter("value", address.valueId()).getResultList();
        // Clear stale first-level-cache state after waiting for another transaction's subject lock.
        result.forEach(em::refresh);
        return result.stream().filter(grant -> Objects.equals(grant.getDimensionValueId(), address.valueId())).toList();
    }
    static List<PermissionPatch.Snapshot> snapshots(List<AccessGrant> rows) {
        return rows.stream().map(grant -> new PermissionPatch.Snapshot(grant.getId(), grant.getVersion(),
            new PermissionPatch.Flags(grant.isCanRead(), grant.isCanUpdate(), grant.isCanDelete()))).toList();
    }
    private void validate(PermissionGrantAddress address, Map<PermissionBinding.Flag, Boolean> dirty) {
        var catalog = catalogs.catalog();
        var bindings = new ArrayList<PermissionBinding>();
        if (address.valueId() == null) catalog.rows().forEach(row -> row.columns().forEach(column -> bindings.add(column.binding())));
        catalog.scope(address.key()).ifPresent(scope -> scope.columns().forEach(column -> bindings.add(column.binding())));
        if (address.key().equals("*") && address.authority() == PermissionBinding.Authority.RLS && address.valueId() == null) {
            for (var flag : PermissionBinding.Flag.values()) bindings.add(new PermissionBinding("*", address.authority(), flag,
                Set.of(switch (flag) { case READ -> org.ipro.rls.c5.C5Operation.READ; case UPDATE -> org.ipro.rls.c5.C5Operation.UPDATE; case DELETE -> org.ipro.rls.c5.C5Operation.DELETE; })));
        }
        var supported = bindings.stream().filter(binding -> binding.authority() == address.authority() && binding.key().equals(address.key()))
            .map(PermissionBinding::flag).collect(java.util.stream.Collectors.toSet());
        if (supported.isEmpty() || !supported.containsAll(dirty.keySet())) throw new IllegalArgumentException("Unsupported permission/flag " + address);
        if (address.valueId() != null) {
            var scope = catalog.scope(address.key()).orElseThrow(() -> new IllegalArgumentException("No scope value source"));
            boolean exists = filters.withDimensionAdministration(em, scope.id(), () -> {
                var cb = em.getCriteriaBuilder(); var query = cb.createQuery(Long.class); var root = query.from(scope.valueType());
                query.select(cb.count(root)).where(cb.equal(root.get("id"), address.valueId()));
                return em.createQuery(query).getSingleResult() == 1;
            });
            if (!exists) throw new IllegalArgumentException("Scope value no longer exists: " + address.valueId());
        }
    }
}
