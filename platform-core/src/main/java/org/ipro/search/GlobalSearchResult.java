package org.ipro.search;

import java.util.Objects;

/**
 * Безопасный DTO результата глобального поиска; UI не получает entity-граф и не должен
 * самостоятельно выполнять дополнительные запросы для построения строки выдачи.
 */
public record GlobalSearchResult(
        int sourceOrder,
        String groupTitle,
        Class<?> entityClass,
        Object entityId,
        String displayValue,
        GlobalSearchMatchKind matchKind,
        String matchedField) {

    public GlobalSearchResult {
        Objects.requireNonNull(groupTitle, "groupTitle");
        Objects.requireNonNull(entityClass, "entityClass");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(displayValue, "displayValue");
        Objects.requireNonNull(matchKind, "matchKind");
        Objects.requireNonNull(matchedField, "matchedField");
    }
}
