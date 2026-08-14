package org.ipro.reportstudio.dom;

/**
 * Агрегация поля в footer-бэнде (Фаза 4, рендер).
 * Применима только к GROUP_FOOTER/REPORT_FOOTER.
 */
public enum ReportFieldAggregation {

    NONE,
    SUM,
    COUNT,
    AVG,
    MIN,
    MAX
}