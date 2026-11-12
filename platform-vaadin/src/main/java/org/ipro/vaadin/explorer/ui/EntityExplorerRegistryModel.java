package org.ipro.vaadin.explorer.ui;

import org.ipro.data.EntityExposure;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import java.util.*;

/** Pure, snapshot-only root projection. It deliberately does not use the detailed field search index. */
public final class EntityExplorerRegistryModel {
    private EntityExplorerRegistryModel() { }
    public record Group(String id, String label, List<ExplorerSnapshot.Entry> entries) {
        public Group { entries = List.copyOf(entries); }
    }
    public record Projection(List<Group> groups, List<ExplorerSnapshot.Entry> entries, int total) {
        public Projection { groups = List.copyOf(groups); entries = List.copyOf(entries); }
    }
    private static final List<EntityKind> KINDS = List.of(EntityKind.CATALOG, EntityKind.DOCUMENT, EntityKind.INFORMATION_REGISTER, EntityKind.PLAIN);
    public static List<EntityKind> kinds() { return KINDS; }
    public static boolean service(ExplorerSnapshot.Entry entry) {
        return entry.descriptor() != null && (entry.descriptor().exposure() == EntityExposure.INTERNAL_STORE
            || entry.descriptor().exposure() == EntityExposure.UNCLASSIFIED);
    }
    public static Projection project(ExplorerSnapshot snapshot, EntityExplorerRegistryState state) {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(state);
        var base = snapshot.roots().stream().filter(entry -> state.showServiceTypes() || !service(entry)).toList();
        String query = state.query().strip().toLowerCase(Locale.ROOT);
        var entries = base.stream().filter(entry -> state.kind() == null || state.kind() == entry.kind())
            .filter(entry -> state.subsystem() == null || (EntityExplorerRegistryState.NO_SUBSYSTEM.equals(state.subsystem())
                ? entry.subsystem().isEmpty() : entry.subsystem().map(sub -> sub.id().equals(state.subsystem())).orElse(false)))
            .filter(entry -> state.exposure() == null || (entry.descriptor() == null
                ? EntityExplorerRegistryState.UNKNOWN_EXPOSURE.equals(state.exposure()) : entry.descriptor().exposure().name().equals(state.exposure())))
            .filter(entry -> !state.onlyErrors() || entry.state() == ExplorerSnapshot.EntryState.BUILD_FAILED
                || entry.countsKnown() && entry.errorCount() > 0)
            .filter(entry -> query.isEmpty() || entry.displayName().value().toLowerCase(Locale.ROOT).contains(query)
                || entry.simpleName().toLowerCase(Locale.ROOT).contains(query))
            .sorted(Comparator.comparing((ExplorerSnapshot.Entry entry) -> entry.displayName().value(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ExplorerSnapshot.Entry::typeFqn)).toList();
        Map<String, List<ExplorerSnapshot.Entry>> groups = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (var entry : entries) {
            boolean byKind = state.grouping() == EntityExplorerRegistryState.Grouping.KIND;
            String id = byKind ? "kind:" + (entry.kind() != null && KINDS.contains(entry.kind()) ? entry.kind().name() : "UNKNOWN")
                : "subsystem:" + entry.subsystem().map(ExplorerSnapshot.SubsystemRef::id).orElse(EntityExplorerRegistryState.NO_SUBSYSTEM);
            labels.put(id, byKind ? EntityExplorerLabels.kind(entry.kind(), true)
                : entry.subsystem().map(sub -> sub.label().value()).orElse("Без подсистемы"));
            groups.computeIfAbsent(id, key -> new ArrayList<>()).add(entry);
        }
        Comparator<String> order = state.grouping() == EntityExplorerRegistryState.Grouping.KIND
            ? Comparator.comparingInt(id -> { int index = List.of("kind:CATALOG", "kind:DOCUMENT", "kind:INFORMATION_REGISTER", "kind:PLAIN").indexOf(id); return index < 0 ? 99 : index; })
            : Comparator.comparing((String id) -> id.endsWith(EntityExplorerRegistryState.NO_SUBSYSTEM))
                .thenComparing(labels::get, String.CASE_INSENSITIVE_ORDER).thenComparing(Comparator.naturalOrder());
        var projected = groups.keySet().stream().sorted(order).map(id -> new Group(id, labels.get(id), groups.get(id))).toList();
        return new Projection(projected, projected.stream().flatMap(group -> group.entries().stream()).toList(), base.size());
    }
}
