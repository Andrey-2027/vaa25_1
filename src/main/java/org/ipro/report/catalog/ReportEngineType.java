package org.ipro.report.catalog;

/**
 * Движок отчёта в едином каталоге. Часть ключа записи: id сущностей разных
 * движков — разные namespace, диспетчеризация действий только по паре
 * (type, id) — см. UnionReport1.md, Р9.
 */
public enum ReportEngineType {
    /** UserDynamicReport — конструктор reportstudio (ReportTemplate). */
    UDR,
    /** JR — шаблоны JasperReports .jrxml (JrxmlTemplate, дизайн в Jaspersoft Studio). */
    JR,
    /** UniDR — simple report recipe stored as JSON. */
    UNIDR
}
