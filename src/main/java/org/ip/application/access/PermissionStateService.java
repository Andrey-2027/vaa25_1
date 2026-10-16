package org.ip.application.access;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsGrantMatching;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only administrative owner for a catalogue matrix or an already fetched scope page. */
@Service
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true)
public class PermissionStateService {
    public record Snapshot(SubjectRef subject, List<SubjectRef> roles,
                           List<PermissionCellState> cells) {
        public Snapshot { roles = List.copyOf(roles); cells = List.copyOf(cells); }
    }

    private final PermissionCatalogService catalogs;
    private final UserRepository users;
    private final RoleRepository roles;
    private final AccessGrantRepository grants;
    private final RlsDimensionRegistry dimensions;
    private final C5PermissionDecisionService c5;

    public PermissionStateService(PermissionCatalogService catalogs, UserRepository users,
                                  RoleRepository roles, AccessGrantRepository grants,
                                  RlsDimensionRegistry dimensions, C5PermissionDecisionService c5) {
        this.catalogs = catalogs; this.users = users; this.roles = roles;
        this.grants = grants; this.dimensions = dimensions; this.c5 = c5;
    }

    public Snapshot objects(SubjectRef.Identity identity) {
        return objectsWithDraft(identity, null);
    }

    public Snapshot objectsWithDraft(SubjectRef.Identity identity, PermissionPatch patch) {
        var requests = new ArrayList<Request>();
        catalogs.catalog().rows().forEach(row -> row.columns().forEach(column ->
            requests.add(new Request(column.binding(), null))));
        if (patch != null && !patch.subject().identity().equals(identity)) throw new IllegalArgumentException("Wrong draft subject");
        return calculate(identity, requests, Map.of(), patch);
    }

    /** Scope membership, filtering and value existence are owned by the paged catalogue (P3). */
    public Snapshot scope(SubjectRef.Identity identity, String scopeId, List<Long> pageIds) {
        return scopeWithDraft(identity, scopeId, pageIds, Map.of());
    }

    public Snapshot scopeWithDraft(SubjectRef.Identity identity, String scopeId, List<Long> pageIds,
                                   Map<Long, PermissionPatch.Flags> draft) {
        return scopeWithPatch(identity, scopeId, pageIds, draft, null);
    }

    public Snapshot scopeWithPatch(SubjectRef.Identity identity, String scopeId, List<Long> pageIds,
                                   Map<Long, PermissionPatch.Flags> draft, PermissionPatch globalDraft) {
        var scope = catalogs.catalog().scope(scopeId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown editable scope " + scopeId));
        if (pageIds.size() > 500) throw new IllegalArgumentException("A scope page is limited to 500 ids");
        var requests = new ArrayList<Request>();
        for (Long id : new LinkedHashSet<>(pageIds)) {
            if (id == null || id <= 0) throw new IllegalArgumentException("A saved positive scope id is required");
            scope.columns().forEach(column -> requests.add(new Request(column.binding(), id)));
        }
        if (draft.size() > 500 || draft.keySet().stream().anyMatch(id -> id == null || id <= 0)
            || draft.values().stream().anyMatch(flags -> !flags.read() && (flags.update() || flags.delete())))
            throw new IllegalArgumentException("Invalid normalized scope draft");
        if (globalDraft != null && !globalDraft.subject().identity().equals(identity)) throw new IllegalArgumentException("Wrong draft subject");
        return calculate(identity, requests, draft, globalDraft);
    }

    /** Explicit editing of all-values and wildcard sources, distinct from any existing row. */
    public Snapshot globalScope(SubjectRef.Identity identity, String scopeId, PermissionPatch draft) {
        var scope = catalogs.catalog().scope(scopeId).orElseThrow(() -> new IllegalArgumentException("Unknown scope"));
        if (draft != null && !draft.subject().identity().equals(identity)) throw new IllegalArgumentException("Wrong draft subject");
        var requests = new ArrayList<Request>();
        for (String key : List.of(scopeId, "*")) for (var column : scope.columns()) {
            var binding = column.binding();
            requests.add(new Request(new PermissionBinding(key, binding.authority(), binding.flag(), binding.operations()), null));
        }
        return calculate(identity, requests, Map.of(), draft, true);
    }

    /** Explicit separate question for a new scope value without id, never a fake existing row. */
    public Snapshot newScopeValue(SubjectRef.Identity identity, String scopeId) {
        var scope = catalogs.catalog().scope(scopeId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown editable scope " + scopeId));
        return calculate(identity, scope.columns().stream()
            .filter(column -> column.binding().flag() != PermissionBinding.Flag.READ)
            .map(column -> new Request(column.binding(), null)).toList());
    }

    private Snapshot calculate(SubjectRef.Identity identity, List<Request> requests) {
        return calculate(identity, requests, Map.of());
    }

    private Snapshot calculate(SubjectRef.Identity identity, List<Request> requests, Map<Long, PermissionPatch.Flags> draft) {
        return calculate(identity, requests, draft, null);
    }

    private Snapshot calculate(SubjectRef.Identity identity, List<Request> requests, Map<Long, PermissionPatch.Flags> draft, PermissionPatch objectDraft) {
        return calculate(identity, requests, draft, objectDraft, false);
    }

    private Snapshot calculate(SubjectRef.Identity identity, List<Request> requests, Map<Long, PermissionPatch.Flags> draft, PermissionPatch objectDraft, boolean globalOnly) {
        SubjectRef subject;
        List<SubjectRef> assigned;
        if (identity.type() == AccessGrant.SubjectType.USER) {
            var user = users.findPermissionSubject(identity.id())
                .orElseThrow(() -> new IllegalArgumentException("User no longer exists"));
            subject = SubjectRef.of(user);
            assigned = user.getRoles().stream().map(SubjectRef::of)
                .sorted(java.util.Comparator.comparingLong(SubjectRef::id)).toList();
        } else {
            subject = SubjectRef.of(roles.findById(identity.id())
                .orElseThrow(() -> new IllegalArgumentException("Role no longer exists")));
            assigned = List.of();
        }
        if (requests.isEmpty()) return new Snapshot(subject, assigned, List.of());
        var origins = new LinkedHashMap<Origin, SubjectRef>();
        origins.put(new Origin(subject.type(), subject.subjectKey()), subject);
        assigned.forEach(role -> origins.put(new Origin(role.type(), role.subjectKey()), role));
        Set<String> keys = new LinkedHashSet<>();
        Set<String> rlsKeys = new LinkedHashSet<>();
        Set<Long> values = new LinkedHashSet<>();
        Set<String> allValueKeys = new LinkedHashSet<>();
        for (var request : requests) {
            keys.add(request.binding.key());
            if (request.valueId != null) values.add(request.valueId);
            if (request.binding.authority() == PermissionBinding.Authority.RLS) {
                rlsKeys.add(request.binding.key());
                if (request.valueId == null && !globalOnly) {
                    allValueKeys.add(request.binding.key());
                    allValueKeys.add("*");
                }
            }
        }
        if (!rlsKeys.isEmpty()) keys.add("*");
        // Non-matching sentinels avoid provider-specific empty IN behaviour.
        values.add(-1L);
        allValueKeys.add("__permission_no_dimension__");
        List<String> roleKeys = origins.keySet().stream()
            .filter(origin -> origin.type == AccessGrant.SubjectType.ROLE).map(Origin::key).toList();
        // A role decision has at least one role; for a user without roles use its key, then
        // discard every unassigned role below. This avoids inventing a role-key sentinel.
        var loaded = new ArrayList<>(grants.findPermissionContributions(keys,
            subject.type() == AccessGrant.SubjectType.USER ? subject.subjectKey() : null,
            roleKeys.isEmpty() ? List.of(subject.subjectKey()) : roleKeys, values, allValueKeys).stream()
            .filter(grant -> origins.containsKey(new Origin(grant.getSubjectType(), grant.getSubjectKey())))
            .toList());
        if (!draft.isEmpty()) {
            String scopeKey = requests.getFirst().binding.key();
            loaded.removeIf(grant -> grant.getSubjectType() == subject.type()
                && grant.getSubjectKey().equals(subject.subjectKey()) && grant.getDimension().equals(scopeKey)
                && grant.getDimensionValueId() != null && draft.containsKey(grant.getDimensionValueId()));
            draft.forEach((id, flags) -> {
                var grant = new AccessGrant(); grant.setId(-id);
                grant.setSubjectType(subject.type()); grant.setSubjectKey(subject.subjectKey());
                grant.setDimension(scopeKey); grant.setDimensionValueId(id);
                grant.setCanRead(flags.read()); grant.setCanUpdate(flags.update()); grant.setCanDelete(flags.delete());
                loaded.add(grant);
            });
        }
        var dirtyAddresses = new LinkedHashSet<PermissionGrantAddress>();
        if (objectDraft != null) {
            long synthetic = -1000000;
            for (var change : objectDraft.changes()) {
                if (change.address().valueId() != null) continue;
                if (!keys.contains(change.address().key())) continue;
                dirtyAddresses.add(change.address());
                loaded.removeIf(grant -> grant.getSubjectType() == subject.type()
                    && grant.getSubjectKey().equals(subject.subjectKey()) && grant.getDimension().equals(change.address().key())
                    && grant.getDimensionValueId() == null);
                var flags = PermissionDraftStore.flags(change.expectedRows()).apply(change.desiredFlags());
                if (change.address().authority() == PermissionBinding.Authority.RLS)
                    flags = new PermissionPatch.Flags(flags.read() || flags.update() || flags.delete(), flags.update(), flags.delete());
                var grant = new AccessGrant(); grant.setId(synthetic--); grant.setSubjectType(subject.type());
                grant.setSubjectKey(subject.subjectKey()); grant.setDimension(change.address().key());
                grant.setCanRead(flags.read()); grant.setCanUpdate(flags.update()); grant.setCanDelete(flags.delete()); loaded.add(grant);
            }
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        if (!rlsKeys.isEmpty()) grants.countPermissionDimensions(rlsKeys)
            .forEach(count -> counts.put(count.getDimension(), count.getGrantCount()));
        var cells = new ArrayList<PermissionCellState>();
        for (var request : new LinkedHashSet<>(requests)) {
            var binding = request.binding;
            C5Operation operation = binding.operations().contains(C5Operation.CREATE)
                ? C5Operation.CREATE : binding.operations().iterator().next();
            List<AccessGrant> matching;
            boolean bootstrap = false;
            if (binding.authority() == PermissionBinding.Authority.C5) {
                matching = c5.contributions(binding.key(), operation, loaded);
            } else {
                matching = loaded.stream().filter(grant -> RlsGrantMatching.matches(grant,
                    binding.key(), request.valueId, operation)).toList();
                if (globalOnly) matching = matching.stream().filter(grant -> grant.getDimensionValueId() == null
                    && grant.getDimension().equals(binding.key())).toList();
                if (request.valueId != null && binding.flag() != PermissionBinding.Flag.READ
                    && loaded.stream().noneMatch(grant -> RlsGrantMatching.matches(grant,
                        binding.key(), request.valueId, C5Operation.READ))) matching = List.of();
                bootstrap = !globalOnly && binding.flag() != PermissionBinding.Flag.READ
                    && binding.flag() != PermissionBinding.Flag.DELETE && request.valueId == null
                    && RlsGrantMatching.bootstrapAllowed(dimensions.kindOf(binding.key()),
                        counts.getOrDefault(binding.key(), 0L));
            }
            var sources = new ArrayList<PermissionContribution>();
            var seenIds = new LinkedHashSet<Long>();
            boolean direct = false;
            for (var grant : matching) {
                var origin = origins.get(new Origin(grant.getSubjectType(), grant.getSubjectKey()));
                var originAddress = new PermissionGrantAddress(binding.authority(), grant.getDimension(), grant.getDimensionValueId());
                direct |= origin.identity().equals(subject.identity())
                    && originAddress.equals(new PermissionGrantAddress(binding.authority(), binding.key(), request.valueId));
                if (seenIds.add(grant.getId())) sources.add(new PermissionContribution(
                    grant.getId() < 0 ? PermissionContribution.Kind.DRAFT_GRANT : PermissionContribution.Kind.STORED_GRANT,
                    origin, grant.getId() < 0 ? null : grant.getId(), originAddress,
                    binding.flag(), grant.getDimension().equals("*") ? "Все измерения RLS"
                        : grant.getDimensionValueId() == null ? "Для всех записей" : "Конкретное значение"));
            }
            if (bootstrap) sources.add(new PermissionContribution(PermissionContribution.Kind.SYSTEM_RULE,
                null, null, null, binding.flag(), "Bootstrap RLS: по измерению нет грантов ни у одного субъекта; "
                    + "разрешено только создание нового значения без id"));
            boolean effective = !sources.isEmpty();
            cells.add(new PermissionCellState(binding,
                new PermissionGrantAddress(binding.authority(), binding.key(), request.valueId),
                direct, effective, !effective || direct, (request.valueId != null && draft.containsKey(request.valueId))
                    || dirtyAddresses.contains(new PermissionGrantAddress(binding.authority(), binding.key(), request.valueId)), sources,
                effective && binding.authority() == PermissionBinding.Authority.C5
                    && binding.key().startsWith("ENTITY:") && binding.operations().contains(C5Operation.UPDATE)
                    && c5.contributions(binding.key(), C5Operation.READ, loaded).isEmpty()
                    ? "Создание разрешено; изменение существующей записи требует также чтения"
                    : effective ? "" : "Право не предоставлено либо отсутствует обязательное чтение"));
        }
        return new Snapshot(subject, assigned, cells);
    }

    private record Request(PermissionBinding binding, Long valueId) { }
    private record Origin(AccessGrant.SubjectType type, String key) { }
}
