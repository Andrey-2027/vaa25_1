package org.ip.application.access;

import java.io.Serializable;
import java.util.*;

/** Workspace-owned, never a singleton bean. Only touched rows and immutable original snapshots. */
public final class PermissionDraftStore implements Serializable {
    private static final class Draft implements Serializable {
        final SubjectRef subject;
        final Map<PermissionGrantAddress, PermissionPatch.Change> changes = new LinkedHashMap<>();
        Draft(SubjectRef subject) { this.subject = subject; }
    }
    private final Map<SubjectRef.Identity, Draft> subjects = new LinkedHashMap<>();

    public void change(SubjectRef subject, PermissionGrantAddress address, PermissionBinding.Flag flag,
                       boolean enabled, List<PermissionPatch.Snapshot> original) {
        var draft = subjects.computeIfAbsent(subject.identity(), ignored -> new Draft(subject));
        if (!draft.subject.subjectKey().equals(subject.subjectKey()))
            throw new IllegalStateException("Subject renamed: reload or cancel its draft");
        var prior = draft.changes.get(address);
        if (prior != null && !new HashSet<>(prior.expectedRows()).equals(new HashSet<>(original)))
            throw new IllegalStateException("Grant baseline changed: resolve conflict before editing");
        List<PermissionPatch.Snapshot> baseline = prior == null ? List.copyOf(original) : prior.expectedRows();
        var desired = new EnumMap<PermissionBinding.Flag, Boolean>(PermissionBinding.Flag.class);
        if (prior != null) desired.putAll(prior.desiredFlags());
        desired.put(flag, enabled);
        if (address.authority() == PermissionBinding.Authority.RLS) {
            if (enabled && flag != PermissionBinding.Flag.READ) desired.put(PermissionBinding.Flag.READ, true);
            if (!enabled && flag == PermissionBinding.Flag.READ) {
                desired.put(PermissionBinding.Flag.UPDATE, false);
                desired.put(PermissionBinding.Flag.DELETE, false);
            }
        }
        var initial = flags(baseline);
        desired.entrySet().removeIf(entry -> value(initial, entry.getKey()) == entry.getValue());
        if (desired.isEmpty()) draft.changes.remove(address);
        else draft.changes.put(address, new PermissionPatch.Change(address, desired, baseline));
        if (draft.changes.isEmpty()) subjects.remove(subject.identity());
    }

    public Optional<PermissionPatch> patch(SubjectRef.Identity identity) {
        var draft = subjects.get(identity);
        return draft == null ? Optional.empty() : Optional.of(new PermissionPatch(draft.subject, List.copyOf(draft.changes.values())));
    }
    public int changeCount(SubjectRef.Identity identity) {
        return patch(identity).map(patch -> patch.changes().stream().mapToInt(change -> change.desiredFlags().size()).sum()).orElse(0);
    }
    public Set<SubjectRef.Identity> dirtySubjects() { return Set.copyOf(subjects.keySet()); }
    public void cancel(SubjectRef.Identity identity) { subjects.remove(identity); }
    /** Call only after the save transaction returned successfully. A conflict leaves the draft intact. */
    public void saved(SubjectRef.Identity identity) { cancel(identity); }
    public Map<Long, PermissionPatch.Flags> scope(SubjectRef.Identity identity, String key) {
        var result = new LinkedHashMap<Long, PermissionPatch.Flags>();
        patch(identity).ifPresent(patch -> patch.changes().stream()
            .filter(change -> change.address().authority() == PermissionBinding.Authority.RLS
                && change.address().key().equals(key) && change.address().valueId() != null)
            .forEach(change -> result.put(change.address().valueId(), flags(change.expectedRows()).apply(change.desiredFlags()))));
        return Map.copyOf(result);
    }
    public static PermissionPatch.Flags flags(List<PermissionPatch.Snapshot> rows) {
        return new PermissionPatch.Flags(rows.stream().anyMatch(row -> row.flags().read()),
            rows.stream().anyMatch(row -> row.flags().update()), rows.stream().anyMatch(row -> row.flags().delete()));
    }
    private static boolean value(PermissionPatch.Flags flags, PermissionBinding.Flag flag) {
        return switch (flag) { case READ -> flags.read(); case UPDATE -> flags.update(); case DELETE -> flags.delete(); };
    }
}
