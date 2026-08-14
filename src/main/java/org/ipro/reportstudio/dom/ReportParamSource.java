package org.ipro.reportstudio.dom;

/**
 * Источник значения параметра при запуске отчёта (Фаза 3).
 * showOnForm=false НЕ означает «скрытое значение» — источник значения
 * отдельная ось: параметр может быть скрыт с формы, но запрашивать
 * значение у пользователя через CONTEXT/DEFAULT/COMPUTED.
 */
public enum ReportParamSource {

    /** Значение вводится на форме запуска отчёта. */
    FORM,
    /** Константа из defaultValue (JSON-представление). */
    DEFAULT,
    /** Значение приходит из контекста запуска (документ/grid: entityClass, selectedIds). */
    CONTEXT,
    /** Предвычисленное платформой значение (computed: now/currentUser/rlsOrg). */
    COMPUTED
}