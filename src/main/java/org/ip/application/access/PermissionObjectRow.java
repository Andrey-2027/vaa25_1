package org.ip.application.access;

import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Presentation metadata only. A column's presence never substitutes for a server allow decision. */
public record PermissionObjectRow(String id, Kind kind, String title, Class<?> entityType,
                                  String groupId, int order, List<Column> columns,
                                  List<String> scopeIds) implements Serializable {
    public enum Kind { ENTITY, ACTION, API, SETTINGS, LEGACY, TECHNICAL }
    public record Column(String title, PermissionBinding binding) implements Serializable {
        public Column {
            if (title == null || title.isBlank()) throw new IllegalArgumentException("Column title is required");
            Objects.requireNonNull(binding, "binding");
        }
    }

    public PermissionObjectRow {
        id = PermissionBinding.requireKey(id);
        Objects.requireNonNull(kind, "kind");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("Row title is required");
        groupId = PermissionBinding.requireKey(groupId);
        columns = List.copyOf(columns);
        scopeIds = List.copyOf(scopeIds);
        if (kind == Kind.ENTITY && entityType == null) throw new IllegalArgumentException("Entity row requires a type");
        var flags = new HashSet<PermissionBinding.Flag>();
        for (Column column : columns) {
            if (!flags.add(column.binding().flag())) throw new IllegalArgumentException("Duplicate visual grant flag");
        }
        if (new HashSet<>(scopeIds).size() != scopeIds.size()) throw new IllegalArgumentException("Duplicate scope link");
    }

    public static String entityId(Class<?> type) {
        return "entity:" + Objects.requireNonNull(type, "type").getName();
    }
}
