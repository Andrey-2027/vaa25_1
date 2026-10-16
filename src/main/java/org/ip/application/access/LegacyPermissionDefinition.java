package org.ip.application.access;

import java.util.List;

/** Explicit metadata adapter for a real legacy owner, never inferred from CHECK_ONLY or a key prefix. */
public record LegacyPermissionDefinition(String key, String title, String groupId, String groupTitle,
                                         List<PermissionObjectRow.Column> columns) {
    public LegacyPermissionDefinition {
        key = PermissionBinding.requireKey(key);
        groupId = PermissionBinding.requireKey(groupId);
        if (title == null || title.isBlank() || groupTitle == null || groupTitle.isBlank()) {
            throw new IllegalArgumentException("Legacy owner titles are required");
        }
        columns = List.copyOf(columns);
        if (columns.isEmpty()) throw new IllegalArgumentException("Legacy owner must declare operative flags");
        for (var column : columns) {
            if (!key.equals(column.binding().key()) || column.binding().authority() != PermissionBinding.Authority.RLS) {
                throw new IllegalArgumentException("Legacy owner must declare its exact RLS binding");
            }
        }
    }
}
