package org.ip.application.access;

import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Immutable hierarchy and shared scope definitions, not subjects' calculated permissions. */
public record PermissionCatalog(List<Group> groups, List<Scope> scopes,
                                List<Diagnostic> diagnostics) implements Serializable {
    public record Group(String id, String title, String parentId, Class<?> subsystemMarker,
                        int order, List<PermissionObjectRow> rows) implements Serializable {
        public Group {
            id = PermissionBinding.requireKey(id);
            if (title == null || title.isBlank()) throw new IllegalArgumentException("Group title is required");
            rows = List.copyOf(rows);
            for (var row : rows) if (!id.equals(row.groupId())) throw new IllegalArgumentException("Wrong row group");
        }
    }

    public record Scope(String id, String title, Class<?> valueType,
                        List<PermissionObjectRow.Column> columns,
                        List<String> affectedObjectIds) implements Serializable {
        public Scope {
            id = PermissionBinding.requireKey(id);
            if (title == null || title.isBlank()) throw new IllegalArgumentException("Scope title is required");
            Objects.requireNonNull(valueType, "valueType");
            columns = List.copyOf(columns);
            affectedObjectIds = List.copyOf(affectedObjectIds);
            for (var column : columns) {
                if (!id.equals(column.binding().key()) || column.binding().authority() != PermissionBinding.Authority.RLS) {
                    throw new IllegalArgumentException("Scope column must bind its own RLS dimension");
                }
            }
        }
    }

    public record Diagnostic(String code, String key, String rowId, String message) implements Serializable {
        public Diagnostic {
            code = PermissionBinding.requireKey(code);
            if (message == null || message.isBlank()) throw new IllegalArgumentException("Diagnostic message is required");
        }
    }

    public PermissionCatalog {
        groups = List.copyOf(groups);
        scopes = List.copyOf(scopes);
        diagnostics = List.copyOf(diagnostics);
        var groupIds = new HashSet<String>();
        var rowIds = new HashSet<String>();
        for (var group : groups) {
            if (!groupIds.add(group.id())) throw new IllegalArgumentException("Duplicate group id");
            for (var row : group.rows()) if (!rowIds.add(row.id())) throw new IllegalArgumentException("Duplicate row id");
        }
        for (var group : groups) {
            if (group.parentId() != null && !groupIds.contains(group.parentId())) throw new IllegalArgumentException("Unknown parent group");
        }
        var scopeIds = new HashSet<String>();
        for (var scope : scopes) {
            if (!scopeIds.add(scope.id())) throw new IllegalArgumentException("Duplicate shared scope");
            if (!rowIds.containsAll(scope.affectedObjectIds())) throw new IllegalArgumentException("Unknown affected row");
        }
    }

    public Stream<PermissionObjectRow> rows() { return groups.stream().flatMap(group -> group.rows().stream()); }
    public Optional<PermissionObjectRow> row(String id) { return rows().filter(row -> row.id().equals(id)).findFirst(); }
    public Optional<Scope> scope(String id) { return scopes.stream().filter(scope -> scope.id().equals(id)).findFirst(); }

    /** Keys with real editable metadata. Diagnostic-only keys are deliberately separate. */
    public Set<String> representedKeys() {
        var keys = new HashSet<String>();
        rows().forEach(row -> row.columns().forEach(column -> keys.add(column.binding().key())));
        scopes.forEach(scope -> keys.add(scope.id()));
        return Set.copyOf(keys);
    }
    public Set<String> diagnosticKeys() {
        var keys = new HashSet<String>();
        diagnostics.forEach(diagnostic -> { if (diagnostic.key() != null) keys.add(diagnostic.key()); });
        return Set.copyOf(keys);
    }
}
