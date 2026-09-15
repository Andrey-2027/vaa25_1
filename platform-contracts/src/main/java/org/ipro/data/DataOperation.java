package org.ipro.data;

/**
 * Намерение операции над сущностью в canonical data path (C4, ADR-0007 §1).
 *
 * <p>API выражает назначение операции, а не persistence-механику. Read-намерения
 * соответствуют сценариям {@link org.ipro.fetch.plan.FetchScenario}: {@code LIST},
 * {@code DETAIL}, {@code LOOKUP}; глобальный поиск использует {@code GLOBAL_SEARCH} при
 * FetchPlan {@code LIST}. Write-намерения — {@code CREATE}, {@code UPDATE}, {@code DELETE}.</p>
 */
public enum DataOperation {
    LIST,
    GLOBAL_SEARCH,
    DETAIL,
    LOOKUP,
    CREATE,
    UPDATE,
    DELETE
}
