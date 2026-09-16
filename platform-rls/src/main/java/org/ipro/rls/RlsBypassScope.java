package org.ipro.rls;

import java.util.Set;
import java.util.Map;

/** Закрытый перечень причин, по которым runtime разрешает выполнить операцию без RLS. */
public enum RlsBypassScope {
    REFERENCE_INTEGRITY_CHECK(Set.of("count all references before delete")),
    RLS_ADMINISTRATION(Set.of(
        "list all JOURNAL dimension values",
        "list all BRANCH dimension values"
    ));

    private final Set<String> allowedReasons;

    RlsBypassScope(Set<String> allowedReasons) {
        this.allowedReasons = allowedReasons;
    }

    void validateReason(String reason) {
        if (!allowedReasons.contains(reason)) {
            throw new IllegalArgumentException(
                "Reason is not allowed for RLS bypass scope " + this);
        }
    }

    String administrationTable(String reason) {
        if (this != RLS_ADMINISTRATION) {
            return null;
        }
        return Map.of(
            "list all JOURNAL dimension values", "journal",
            "list all BRANCH dimension values", "branch"
        ).get(reason);
    }
}
